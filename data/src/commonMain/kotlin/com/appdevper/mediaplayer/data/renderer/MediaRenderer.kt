package com.appdevper.mediaplayer.data.renderer

import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.platform.MimeTypes
import com.appdevper.mediaplayer.data.server.Descriptions
import com.appdevper.mediaplayer.data.upnp.Didl
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml
import com.appdevper.mediaplayer.data.upnp.formatUpnpTime
import com.appdevper.mediaplayer.data.upnp.host.GenaPublisher
import com.appdevper.mediaplayer.data.upnp.host.SoapRequest
import com.appdevper.mediaplayer.data.upnp.host.SsdpAdvertiser
import com.appdevper.mediaplayer.data.upnp.host.receiveSoap
import com.appdevper.mediaplayer.data.upnp.host.respondSoap
import com.appdevper.mediaplayer.data.upnp.host.respondSoapFault
import com.appdevper.mediaplayer.data.upnp.host.respondXml
import com.appdevper.mediaplayer.data.upnp.host.startHttpServer
import com.appdevper.mediaplayer.data.upnp.parseUpnpTime
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class RendererConfig(val name: String, val udn: String)

/**
 * DLNA Digital Media Renderer (DMR): lets other devices play on this one. Implements AVTransport,
 * RenderingControl and ConnectionManager, emits what controllers ask for on [commands] and answers
 * status queries from the playback the app reports through [publish].
 */
class MediaRenderer(
    private val ssdp: SsdpEngine,
    http: HttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Stopped)
    val state: StateFlow<ReceiverStatus> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<CastCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<CastCommand> = _commands.asSharedFlow()

    /** Everything the SOAP answers are built from. */
    private data class Model(
        val uri: String? = null,
        val metadata: String = "",
        val entry: MediaEntry? = null,
        val playback: ReceiverPlayback = ReceiverPlayback(),
        /** State a controller just asked for, reported until the player catches up (or [EXPECT_FOR] passes). */
        val expected: TransportState? = null,
        val expectedAt: TimeSource.Monotonic.ValueTimeMark? = null,
        /** A Load was emitted and the app hasn't published that item yet. */
        val pendingLoad: Boolean = false,
    ) {
        /** The app is playing what was cast (not something the user picked since), or is about to. */
        val current: Boolean get() = uri != null && (pendingLoad || playback.entry?.streamUrl == uri)
    }

    private val model = MutableStateFlow(Model())

    private val avtEvents = GenaPublisher(http, scope) { GenaPublisher.lastChange(avtEvent(model.value)) }
    private val rcsEvents = GenaPublisher(http, scope) { GenaPublisher.lastChange(rcsEvent(model.value)) }
    private val cmEvents = GenaPublisher(http, scope) {
        GenaPublisher.properties(listOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK_PROTOCOLS, "CurrentConnectionIDs" to "0"))
    }

    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private var advertiser: SsdpAdvertiser? = null
    private var config: RendererConfig? = null
    private val jobs = mutableListOf<Job>()

    /** Description URL while running. */
    var location: String? = null
        private set

    fun publish(playback: ReceiverPlayback) = model.update {
        it.copy(playback = playback, pendingLoad = it.pendingLoad && playback.entry?.streamUrl != it.uri)
    }

    suspend fun start(config: RendererConfig) = mutex.withLock {
        stopLocked(byeBye = true)
        try {
            if (!ssdp.ensureStarted()) throw IllegalStateException("Not connected to a local network")
            val ip = ssdp.localIp ?: throw IllegalStateException("No LAN address")
            val (engine, port) = startHttpServer(PORTS) { routes() }
            server = engine
            this.config = config
            location = "http://$ip:$port/description.xml"
            advertiser = SsdpAdvertiser(
                ssdp, scope, config.udn,
                listOf(UpnpTypes.MEDIA_RENDERER, UpnpTypes.AV_TRANSPORT, UpnpTypes.RENDERING_CONTROL, UpnpTypes.CONNECTION_MANAGER),
                location = location!!,
            ).also { it.start() }
            // Position isn't evented (per spec), so these only fire on real changes.
            jobs += scope.launch { model.map(::avtEvent).distinctUntilChanged().collect { avtEvents.notifyChanged() } }
            jobs += scope.launch { model.map(::rcsEvent).distinctUntilChanged().collect { rcsEvents.notifyChanged() } }
            _state.value = ReceiverStatus.Running(config.name)
        } catch (e: Throwable) {
            stopLocked(byeBye = false)
            _state.value = ReceiverStatus.Failed(e.message ?: e::class.simpleName ?: "Failed to start")
        }
    }

    suspend fun stop() = mutex.withLock {
        stopLocked(byeBye = true)
        _state.value = ReceiverStatus.Stopped
    }

    private suspend fun stopLocked(byeBye: Boolean) {
        advertiser?.stop(byeBye)
        advertiser = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        listOf(avtEvents, rcsEvents, cmEvents).forEach { it.clear() }
        server?.let { s -> withContext(Dispatchers.IO) { runCatching { s.stop(200, 1_000) } } }
        server = null
        config = null
        location = null
        model.update { Model(playback = it.playback) }
    }

    // region HTTP

    private fun Route.routes() {
        get("/description.xml") {
            val cfg = config ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respondXml(RendererDescriptions.device(cfg.udn, cfg.name))
        }
        get("/avt.xml") { call.respondXml(RendererDescriptions.avTransport) }
        get("/rcs.xml") { call.respondXml(RendererDescriptions.renderingControl) }
        get("/cm.xml") { call.respondXml(Descriptions.connectionManager) }
        post("/avt/control") { handleAvTransport(call) }
        post("/rcs/control") { handleRenderingControl(call) }
        post("/cm/control") { handleConnectionManager(call) }
        for ((path, events) in listOf("/avt/event" to avtEvents, "/rcs/event" to rcsEvents, "/cm/event" to cmEvents)) {
            route(path, HttpMethod("SUBSCRIBE")) { handle { events.subscribe(call) } }
            route(path, HttpMethod("UNSUBSCRIBE")) { handle { events.unsubscribe(call) } }
        }
    }

    /** Parses the request and checks it targets instance 0, the only one there is. */
    private suspend fun ApplicationCall.receiveInstanceRequest(): SoapRequest? {
        val req = receiveSoap()
        if (req == null) {
            respondSoapFault(402, "Invalid Args")
            return null
        }
        if ((req.args["InstanceID"] ?: "0") != "0") {
            respondSoapFault(718, "Invalid InstanceID")
            return null
        }
        return req
    }

    private suspend fun handleAvTransport(call: ApplicationCall) {
        val st = UpnpTypes.AV_TRANSPORT
        val req = call.receiveInstanceRequest() ?: return
        val m = model.value
        when (val action = req.action) {
            "SetAVTransportURI" -> {
                val uri = req.args["CurrentURI"].orEmpty().trim()
                if (uri.isEmpty()) {
                    model.update { Model(playback = it.playback) }
                    return call.respondSoap(st, action)
                }
                val metadata = req.args["CurrentURIMetaData"].orEmpty()
                val entry = entryFor(uri, metadata) ?: return call.respondSoapFault(716, "Resource not found")
                model.update { it.copy(uri = uri, metadata = metadata, entry = entry, pendingLoad = true).expect(TransportState.STOPPED) }
                _commands.emit(CastCommand.Load(entry, autoPlay = false))
                call.respondSoap(st, action)
            }
            "Play" -> {
                val entry = m.entry ?: return call.respondSoapFault(701, "Transition not available")
                if (m.current) {
                    _commands.emit(CastCommand.Play)
                    model.update { it.expect(TransportState.PLAYING) }
                } else {
                    // The user played something else here since: bring the cast item back.
                    _commands.emit(CastCommand.Load(entry, autoPlay = true))
                    model.update { it.copy(pendingLoad = true).expect(TransportState.PLAYING) }
                }
                call.respondSoap(st, action)
            }
            "Pause" -> command(call, action, CastCommand.Pause, TransportState.PAUSED)
            "Stop" -> command(call, action, CastCommand.Stop, TransportState.STOPPED)
            "Seek" -> {
                if (!m.current) return call.respondSoapFault(701, "Transition not available")
                if (req.args["Unit"] !in setOf("REL_TIME", "ABS_TIME")) return call.respondSoapFault(710, "Seek mode not supported")
                val target = req.args["Target"]?.let(::parseUpnpTime) ?: return call.respondSoapFault(711, "Illegal seek target")
                _commands.emit(CastCommand.Seek(target))
                call.respondSoap(st, action)
            }
            "Next", "Previous" -> call.respondSoapFault(701, "Transition not available")
            "SetPlayMode" ->
                if (req.args["NewPlayMode"] == "NORMAL") call.respondSoap(st, action) else call.respondSoapFault(712, "Play mode not supported")
            "GetTransportInfo" -> call.respondSoap(
                st, action,
                listOf("CurrentTransportState" to upnpState(transportState(m)), "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1"),
            )
            "GetPositionInfo" -> {
                val status = m.playback.status.takeIf { m.playback.entry?.streamUrl == m.uri }
                val duration = status?.durationMs?.takeIf { it > 0 } ?: m.entry?.durationMs
                val position = formatUpnpTime(status?.positionMs ?: 0)
                call.respondSoap(
                    st, action,
                    listOf(
                        "Track" to if (m.uri != null) "1" else "0",
                        "TrackDuration" to (duration?.let(::formatUpnpTime) ?: "0:00:00"),
                        "TrackMetaData" to m.metadata,
                        "TrackURI" to m.uri.orEmpty(),
                        "RelTime" to position,
                        "AbsTime" to position,
                        "RelCount" to Int.MAX_VALUE.toString(),
                        "AbsCount" to Int.MAX_VALUE.toString(),
                    ),
                )
            }
            "GetMediaInfo" -> call.respondSoap(
                st, action,
                listOf(
                    "NrTracks" to if (m.uri != null) "1" else "0",
                    "MediaDuration" to (m.entry?.durationMs?.let(::formatUpnpTime) ?: "0:00:00"),
                    "CurrentURI" to m.uri.orEmpty(),
                    "CurrentURIMetaData" to m.metadata,
                    "NextURI" to "",
                    "NextURIMetaData" to "",
                    "PlayMedium" to if (m.uri != null) "NETWORK" else "NONE",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                ),
            )
            "GetDeviceCapabilities" -> call.respondSoap(
                st, action,
                listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED"),
            )
            "GetTransportSettings" -> call.respondSoap(st, action, listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
            "GetCurrentTransportActions" -> call.respondSoap(st, action, listOf("Actions" to transportActions(m)))
            else -> call.respondSoapFault(401, "Invalid Action")
        }
    }

    private suspend fun command(call: ApplicationCall, action: String, command: CastCommand, expected: TransportState) {
        if (!model.value.current) return call.respondSoapFault(701, "Transition not available")
        _commands.emit(command)
        model.update { it.expect(expected) }
        call.respondSoap(UpnpTypes.AV_TRANSPORT, action)
    }

    private suspend fun handleRenderingControl(call: ApplicationCall) {
        val st = UpnpTypes.RENDERING_CONTROL
        val req = call.receiveInstanceRequest() ?: return
        val playback = model.value.playback
        when (val action = req.action) {
            "GetVolume" -> call.respondSoap(st, action, listOf("CurrentVolume" to playback.volume.toString()))
            "SetVolume" -> {
                val volume = req.args["DesiredVolume"]?.toIntOrNull()?.takeIf { it in 0..100 }
                    ?: return call.respondSoapFault(402, "Invalid Args")
                model.update { it.copy(playback = it.playback.copy(volume = volume)) }
                _commands.emit(CastCommand.SetVolume(volume))
                call.respondSoap(st, action)
            }
            "GetMute" -> call.respondSoap(st, action, listOf("CurrentMute" to if (playback.muted) "1" else "0"))
            "SetMute" -> {
                val muted = when (req.args["DesiredMute"]?.lowercase()) {
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> return call.respondSoapFault(402, "Invalid Args")
                }
                model.update { it.copy(playback = it.playback.copy(muted = muted)) }
                _commands.emit(CastCommand.SetMute(muted))
                call.respondSoap(st, action)
            }
            "ListPresets" -> call.respondSoap(st, action, listOf("CurrentPresetNameList" to "FactoryDefaults"))
            "SelectPreset" -> call.respondSoap(st, action)
            else -> call.respondSoapFault(401, "Invalid Action")
        }
    }

    private suspend fun handleConnectionManager(call: ApplicationCall) {
        val st = UpnpTypes.CONNECTION_MANAGER
        when (val action = call.receiveSoap()?.action) {
            "GetProtocolInfo" -> call.respondSoap(st, action, listOf("Source" to "", "Sink" to SINK_PROTOCOLS))
            "GetCurrentConnectionIDs" -> call.respondSoap(st, action, listOf("ConnectionIDs" to "0"))
            "GetCurrentConnectionInfo" -> call.respondSoap(
                st, action,
                listOf(
                    "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "",
                    "PeerConnectionManager" to "", "PeerConnectionID" to "-1",
                    "Direction" to "Input", "Status" to "OK",
                ),
            )
            else -> call.respondSoapFault(401, "Invalid Action")
        }
    }

    // endregion

    // region state

    private fun Model.expect(state: TransportState) = copy(expected = state, expectedAt = TimeSource.Monotonic.markNow())

    private fun transportState(m: Model): TransportState {
        if (m.uri == null) return TransportState.NO_MEDIA
        if (!m.current) return TransportState.STOPPED
        val reported = when {
            m.playback.entry?.streamUrl != m.uri -> TransportState.TRANSITIONING // load on its way to the player
            else -> m.playback.status?.state ?: TransportState.TRANSITIONING // loaded, player not up yet
        }
        val expected = m.expected
        val waiting = expected != null && reported != expected && m.expectedAt?.let { it.elapsedNow() < EXPECT_FOR } == true
        return when {
            !waiting -> reported
            expected == TransportState.PLAYING -> TransportState.TRANSITIONING
            else -> expected!!
        }
    }

    private fun upnpState(state: TransportState): String = when (state) {
        TransportState.PLAYING -> "PLAYING"
        TransportState.PAUSED -> "PAUSED_PLAYBACK"
        TransportState.TRANSITIONING -> "TRANSITIONING"
        TransportState.NO_MEDIA -> "NO_MEDIA_PRESENT"
        TransportState.STOPPED, TransportState.UNKNOWN -> "STOPPED"
    }

    private fun transportActions(m: Model): String = when (transportState(m)) {
        TransportState.NO_MEDIA -> ""
        TransportState.PLAYING, TransportState.TRANSITIONING -> "Pause,Stop,Seek"
        else -> "Play,Stop,Seek"
    }

    private fun avtEvent(m: Model): String = buildString {
        append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">")
        val state = transportState(m)
        val duration = (m.playback.status?.durationMs?.takeIf { m.playback.entry?.streamUrl == m.uri && it > 0 } ?: m.entry?.durationMs)?.let(::formatUpnpTime) ?: "0:00:00"
        for ((k, v) in listOf(
            "TransportState" to upnpState(state),
            "TransportStatus" to "OK",
            "CurrentPlayMode" to "NORMAL",
            "NumberOfTracks" to if (m.uri != null) "1" else "0",
            "CurrentTrack" to if (m.uri != null) "1" else "0",
            "AVTransportURI" to m.uri.orEmpty(),
            "AVTransportURIMetaData" to m.metadata,
            "CurrentTrackURI" to m.uri.orEmpty(),
            "CurrentTrackMetaData" to m.metadata,
            "CurrentTrackDuration" to duration,
            "CurrentMediaDuration" to duration,
            "CurrentTransportActions" to transportActions(m),
        )) append("<$k val=\"").append(Xml.escape(v)).append("\"/>")
        append("</InstanceID></Event>")
    }

    private fun rcsEvent(m: Model): String =
        "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"${m.playback.volume}\"/>" +
            "<Mute channel=\"Master\" val=\"${if (m.playback.muted) 1 else 0}\"/>" +
            "</InstanceID></Event>"

    // endregion

    companion object {
        private val PORTS = listOf(8201, 8202, 8203, 8204, 0)
        private val EXPECT_FOR = 3.seconds

        private val SINK_PROTOCOLS = listOf(
            "video/mp4", "video/x-matroska", "video/webm", "video/quicktime", "video/mpeg", "video/mp2t", "video/x-msvideo",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/wav", "audio/x-wav", "audio/ogg",
            "image/jpeg", "image/png", "image/gif", "image/webp",
        ).joinToString(",") { "http-get:*:$it:*" }

        /**
         * What to play for a SetAVTransportURI: title/kind/art from the DIDL-Lite metadata when the
         * controller sent some, otherwise guessed from the URL. Only http(s) URLs are accepted.
         */
        fun entryFor(uri: String, metadata: String): MediaEntry? {
            // Url() accepts relative junk, so check the scheme on the raw string first.
            if (!uri.startsWith("http://", ignoreCase = true) && !uri.startsWith("https://", ignoreCase = true)) return null
            val url = runCatching { Url(uri) }.getOrNull()?.takeIf { it.host.isNotEmpty() } ?: return null
            val didl = runCatching { Didl.parse(metadata).firstOrNull { !it.isContainer } }.getOrNull()
            val fileName = url.segments.lastOrNull().orEmpty()
            val mime = didl?.primaryResource?.mimeType?.takeIf { it.isNotEmpty() } ?: MimeTypes.fromFileName(fileName)
            val kind = didl?.kind?.takeIf { it != MediaKind.OTHER }
                ?: mime?.let(MimeTypes::kindOf)?.takeIf { it != MediaKind.OTHER }
                ?: MediaKind.VIDEO
            val base = didl?.toDomain() ?: MediaEntry(id = uri, parentId = "-1", title = fileName.ifEmpty { url.host }, kind = kind)
            return base.copy(id = uri, kind = kind, streamUrl = uri, mimeType = mime ?: base.mimeType)
        }
    }
}

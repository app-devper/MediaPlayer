package com.appdevper.mediaplayer.data.server

import com.appdevper.mediaplayer.data.platform.LocalMedia
import com.appdevper.mediaplayer.data.platform.MediaLibrary
import com.appdevper.mediaplayer.data.platform.ServerHost
import com.appdevper.mediaplayer.data.platform.platformName
import com.appdevper.mediaplayer.data.upnp.Didl
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml
import com.appdevper.mediaplayer.data.upnp.host.SsdpAdvertiser
import com.appdevper.mediaplayer.data.upnp.host.receiveSoap
import com.appdevper.mediaplayer.data.upnp.host.respondSoap
import com.appdevper.mediaplayer.data.upnp.host.respondSoapFault
import com.appdevper.mediaplayer.data.upnp.host.respondXml
import com.appdevper.mediaplayer.data.upnp.host.startHttpServer
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.Route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.random.Random

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val name: String, val url: String, val counts: Map<MediaKind, Int>) : ServerState
    data class Failed(val message: String) : ServerState
}

data class ServerConfig(
    val name: String,
    val udn: String,
    val kinds: Set<MediaKind>,
)

/**
 * DLNA Digital Media Server (DMS) — the KMP port of the old `MediaServer`/`MediaUpnpService`
 * (Cling LocalDevice + NanoHTTPD). Publishes local media over HTTP and announces itself via SSDP.
 */
class MediaServer(
    private val ssdp: SsdpEngine,
    private val scope: CoroutineScope,
    private val host: ServerHost = ServerHost.None,
) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private var advertiser: SsdpAdvertiser? = null
    private var tree: ContentTree? = null
    private var library: MediaLibrary? = null
    private var config: ServerConfig? = null
    private var systemUpdateId = 1

    suspend fun start(config: ServerConfig, library: MediaLibrary) = mutex.withLock {
        stopLocked(announce = true)
        _state.value = ServerState.Starting
        try {
            if (!ssdp.ensureStarted()) throw IllegalStateException("Not connected to a local network")
            val ip = ssdp.localIp ?: throw IllegalStateException("No LAN address")
            val media = library.scan(config.kinds)
            val (engine, port) = startHttpServer(PORTS) { routes() }
            val baseUrl = "http://$ip:$port"
            this.server = engine
            this.library = library
            this.config = config
            this.tree = ContentTree(media, baseUrl)
            systemUpdateId++
            advertiser = SsdpAdvertiser(
                ssdp, scope, config.udn,
                listOf(UpnpTypes.MEDIA_SERVER, UpnpTypes.CONTENT_DIRECTORY, UpnpTypes.CONNECTION_MANAGER),
                location = "$baseUrl/description.xml",
            ).also { it.start() }
            host.onServerStarted(config.name)
            _state.value = ServerState.Running(config.name, baseUrl, tree!!.counts)
        } catch (e: Throwable) {
            stopLocked(announce = false)
            _state.value = ServerState.Failed(e.message ?: e::class.simpleName ?: "Failed to start")
        }
    }

    suspend fun stop() = mutex.withLock {
        stopLocked(announce = true)
        _state.value = ServerState.Stopped
    }

    private suspend fun stopLocked(announce: Boolean) {
        advertiser?.stop(byeBye = announce)
        advertiser = null
        server?.let { s -> withContext(Dispatchers.IO) { runCatching { s.stop(200, 1_000) } } }
        if (server != null) host.onServerStopped()
        server = null
        tree = null
        config = null
    }

    // region HTTP

    private fun Route.routes() {
        get("/") {
            val cfg = config
            call.respondText("<html><body><h3>${Xml.escape(cfg?.name.orEmpty())}</h3>DLNA media server is running.</body></html>", ContentType.Text.Html)
        }
        get("/description.xml") {
            val cfg = config ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respondXml(Descriptions.device(cfg.udn, cfg.name, platformName))
        }
        get("/cds.xml") { call.respondXml(Descriptions.contentDirectory) }
        get("/cm.xml") { call.respondXml(Descriptions.connectionManager) }
        post("/cds/control") { handleContentDirectory(call) }
        post("/cm/control") { handleConnectionManager(call) }
        for (path in listOf("/cds/event", "/cm/event")) {
            route(path, HttpMethod("SUBSCRIBE")) {
                handle {
                    call.response.header("SID", call.request.header("SID") ?: "uuid:${randomUuid()}")
                    call.response.header("TIMEOUT", "Second-1800")
                    call.respond(HttpStatusCode.OK, "")
                }
            }
            route(path, HttpMethod("UNSUBSCRIBE")) { handle { call.respond(HttpStatusCode.OK, "") } }
        }
        get("/media/{file}") { serveMedia(call, headOnly = false) }
        head("/media/{file}") { serveMedia(call, headOnly = true) }
    }

    private suspend fun handleContentDirectory(call: ApplicationCall) {
        val st = UpnpTypes.CONTENT_DIRECTORY
        val body = call.receiveSoap() ?: return call.respondSoapFault(402, "Invalid Args")
        val t = tree ?: return call.respondSoapFault(501, "Server stopped")
        when (val action = body.action) {
            "GetSearchCapabilities" -> call.respondSoap(st, action, listOf("SearchCaps" to ""))
            "GetSortCapabilities" -> call.respondSoap(st, action, listOf("SortCaps" to ""))
            "GetSystemUpdateID" -> call.respondSoap(st, action, listOf("Id" to systemUpdateId.toString()))
            "Browse" -> {
                val id = body.args["ObjectID"].orEmpty().ifEmpty { ContentTree.ROOT_ID }
                val start = body.args["StartingIndex"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val count = body.args["RequestedCount"]?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE
                val (objects, total) = if (body.args["BrowseFlag"] == "BrowseMetadata") {
                    val o = t.metadata(id) ?: return call.respondSoapFault(701, "No such object")
                    listOf(o) to 1
                } else {
                    val all = t.children(id) ?: return call.respondSoapFault(701, "No such object")
                    all.drop(start).take(count) to all.size
                }
                call.respondSoap(
                    st, action,
                    listOf(
                        "Result" to Didl.generate(objects),
                        "NumberReturned" to objects.size.toString(),
                        "TotalMatches" to total.toString(),
                        "UpdateID" to systemUpdateId.toString(),
                    ),
                )
            }
            else -> call.respondSoapFault(401, "Invalid Action")
        }
    }

    private suspend fun handleConnectionManager(call: ApplicationCall) {
        val st = UpnpTypes.CONNECTION_MANAGER
        when (val action = call.receiveSoap()?.action) {
            "GetProtocolInfo" -> call.respondSoap(st, action, listOf("Source" to SOURCE_PROTOCOLS, "Sink" to ""))
            "GetCurrentConnectionIDs" -> call.respondSoap(st, action, listOf("ConnectionIDs" to "0"))
            "GetCurrentConnectionInfo" -> call.respondSoap(
                st, action,
                listOf(
                    "RcsID" to "-1", "AVTransportID" to "-1", "ProtocolInfo" to "",
                    "PeerConnectionManager" to "", "PeerConnectionID" to "-1",
                    "Direction" to "Output", "Status" to "OK",
                ),
            )
            else -> call.respondSoapFault(401, "Invalid Action")
        }
    }

    private suspend fun serveMedia(call: ApplicationCall, headOnly: Boolean) {
        val lib = library
        val media = call.parameters["file"]?.let { tree?.mediaForPath(it) }
        if (lib == null || media == null) return call.respond(HttpStatusCode.NotFound)

        val range = call.request.header(HttpHeaders.Range)?.let { parseRange(it, media.size) }
        if (call.request.header(HttpHeaders.Range) != null && range == null) {
            call.response.header(HttpHeaders.ContentRange, "bytes */${media.size}")
            return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val start = range?.first ?: 0L
        val length = range?.let { it.last - it.first + 1 } ?: media.size
        val contentType = ContentType.parse(media.mimeType)
        val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        val extra = buildList {
            add(HttpHeaders.AcceptRanges to listOf("bytes"))
            add("transferMode.dlna.org" to listOf(if (media.kind == MediaKind.IMAGE) "Interactive" else "Streaming"))
            add("contentFeatures.dlna.org" to listOf(Didl.protocolInfo(media.mimeType).substringAfterLast(':')))
            if (range != null) add(HttpHeaders.ContentRange to listOf("bytes ${range.first}-${range.last}/${media.size}"))
        }
        val headers = headersOf(*extra.toTypedArray())

        if (headOnly) {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentType = contentType
                override val contentLength = length
                override val status = status
                override val headers: Headers = headers
            })
            return
        }
        call.respond(MediaContent(lib, media, start, length, contentType, status, headers))
    }

    private class MediaContent(
        private val library: MediaLibrary,
        private val media: LocalMedia,
        private val start: Long,
        private val length: Long,
        override val contentType: ContentType,
        override val status: HttpStatusCode,
        override val headers: Headers,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = length

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val source = withContext(Dispatchers.IO) { library.open(media, start) }
            try {
                val buffer = Buffer()
                var remaining = length
                while (remaining > 0) {
                    val n = withContext(Dispatchers.IO) { source.readAtMostTo(buffer, minOf(CHUNK, remaining)) }
                    if (n <= 0) break
                    remaining -= n
                    channel.writeFully(buffer.readByteArray())
                    channel.flush()
                }
            } finally {
                runCatching { source.close() }
            }
        }
    }

    // endregion

    companion object {
        private val PORTS = listOf(8191, 8192, 8193, 8194, 0)
        private const val CHUNK = 64L * 1024

        private val SOURCE_PROTOCOLS = listOf(
            "video/mp4", "video/x-matroska", "video/mpeg", "video/quicktime", "video/x-msvideo", "video/webm", "video/mp2t",
            "audio/mpeg", "audio/mp4", "audio/flac", "audio/wav", "audio/aac", "audio/ogg",
            "image/jpeg", "image/png", "image/gif", "image/webp",
        ).joinToString(",") { "http-get:*:$it:*" }

        /** Parses a single `bytes=a-b` / `bytes=a-` / `bytes=-n` range. Multi-range is served as the first range. */
        fun parseRange(header: String, size: Long): LongRange? {
            if (size <= 0) return null
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            val range = when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) return null
                    (size - n).coerceAtLeast(0)..(size - 1)
                }
                else -> {
                    val s = a.toLongOrNull() ?: return null
                    val e = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                    s..e
                }
            }
            return range.takeIf { it.first in 0 until size && it.last >= it.first }
        }

        fun randomUuid(): String {
            val b = Random.nextBytes(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}

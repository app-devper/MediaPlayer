package com.appdevper.mediaplayer.data.server

import com.appdevper.mediaplayer.data.platform.LocalMedia
import com.appdevper.mediaplayer.data.platform.MediaLibrary
import com.appdevper.mediaplayer.data.platform.ServerHost
import com.appdevper.mediaplayer.data.upnp.Didl
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.data.upnp.UpnpTypes
import com.appdevper.mediaplayer.data.upnp.Xml
import com.appdevper.mediaplayer.data.upnp.host.ConnectionManager
import com.appdevper.mediaplayer.data.upnp.host.DeviceSpec
import com.appdevper.mediaplayer.data.upnp.host.GenaPublisher
import com.appdevper.mediaplayer.data.upnp.host.LocalDevice
import com.appdevper.mediaplayer.data.upnp.host.LocalService
import com.appdevper.mediaplayer.data.upnp.host.SoapReply
import com.appdevper.mediaplayer.data.upnp.host.SoapRequest
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
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
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

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
    ssdp: SsdpEngine,
    http: HttpClient,
    scope: CoroutineScope,
    private val host: ServerHost = ServerHost.None,
) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val device = LocalDevice(ssdp, http, scope, LocalDevice.SERVER_PORTS + 0)
    private var running = false
    private var tree: ContentTree? = null
    private var library: MediaLibrary? = null
    private var config: ServerConfig? = null
    private var systemUpdateId = 1

    suspend fun start(config: ServerConfig, library: MediaLibrary) = mutex.withLock {
        stopLocked(announce = true)
        _state.value = ServerState.Starting
        try {
            val media = library.scan(config.kinds)
            this.library = library
            this.config = config
            device.start(spec(config)) { routes() }
            running = true
            val baseUrl = device.baseUrl!!
            this.tree = ContentTree(media, baseUrl)
            systemUpdateId++
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
        device.stop(byeBye = announce)
        if (running) host.onServerStopped()
        running = false
        tree = null
        config = null
    }

    private fun spec(config: ServerConfig) = DeviceSpec(
        type = UpnpTypes.MEDIA_SERVER,
        name = config.name,
        udn = config.udn,
        description = "MediaPlayer DLNA server",
        dlnaDoc = "DMS-1.50",
        services = listOf(
            LocalService(
                type = UpnpTypes.CONTENT_DIRECTORY,
                id = CONTENT_DIRECTORY_ID,
                path = "cds",
                scpd = Descriptions.contentDirectory,
                events = { GenaPublisher.properties(listOf("SystemUpdateID" to systemUpdateId.toString())) },
                control = ::contentDirectory,
            ),
            ConnectionManager.service(source = SOURCE_PROTOCOLS, sink = ""),
        ),
        presentation = true,
    )

    // region HTTP

    private fun Route.routes() {
        get("/") {
            val cfg = config
            call.respondText("<html><body><h3>${Xml.escape(cfg?.name.orEmpty())}</h3>DLNA media server is running.</body></html>", ContentType.Text.Html)
        }
        get("/media/{file}") { serveMedia(call, headOnly = false) }
        get("/subtitle/{id}/{n}/{name}") { serveSubtitle(call) }
        head("/media/{file}") { serveMedia(call, headOnly = true) }
    }

    private fun contentDirectory(request: SoapRequest): SoapReply {
        val t = tree ?: return SoapReply.Fault(501, "Server stopped")
        return when (request.action) {
            "GetSearchCapabilities" -> SoapReply.Ok(listOf("SearchCaps" to ""))
            "GetSortCapabilities" -> SoapReply.Ok(listOf("SortCaps" to ""))
            "GetSystemUpdateID" -> SoapReply.Ok(listOf("Id" to systemUpdateId.toString()))
            "Browse" -> {
                val id = request.args["ObjectID"].orEmpty().ifEmpty { ContentTree.ROOT_ID }
                val start = request.args["StartingIndex"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val count = request.args["RequestedCount"]?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE
                val (objects, total) = if (request.args["BrowseFlag"] == "BrowseMetadata") {
                    val o = t.metadata(id) ?: return SoapReply.Fault(701, "No such object")
                    listOf(o) to 1
                } else {
                    val all = t.children(id) ?: return SoapReply.Fault(701, "No such object")
                    all.drop(start).take(count) to all.size
                }
                SoapReply.Ok(
                    listOf(
                        "Result" to Didl.generate(objects),
                        "NumberReturned" to objects.size.toString(),
                        "TotalMatches" to total.toString(),
                        "UpdateID" to systemUpdateId.toString(),
                    ),
                )
            }
            else -> SoapReply.invalidAction
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

    /** A video's sidecar subtitle file, by its place in the item (only files the scan found). */
    private suspend fun serveSubtitle(call: ApplicationCall) {
        val lib = library
        val n = call.parameters["n"]?.toIntOrNull()
        val subtitle = call.parameters["id"]?.let { id -> n?.let { tree?.subtitle(id, it) } }
        if (lib == null || subtitle == null) return call.respond(HttpStatusCode.NotFound)
        val bytes = withContext(Dispatchers.IO) { runCatching { readSubtitle(lib.openSubtitle(subtitle)) }.getOrNull() }
            ?: return call.respond(HttpStatusCode.NotFound)
        val type = if (subtitle.fileName.endsWith(".vtt", ignoreCase = true)) "text/vtt" else "text/srt"
        call.respondBytes(bytes, ContentType.parse(type))
    }

    /** The whole file, or null when it's larger than any subtitle file should be. */
    private fun readSubtitle(source: RawSource): ByteArray? = source.use {
        val buffer = Buffer()
        while (it.readAtMostTo(buffer, CHUNK) > 0) if (buffer.size > MAX_SUBTITLE_BYTES) return null
        buffer.readByteArray()
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
        private const val CONTENT_DIRECTORY_ID = "ContentDirectory"
        private const val CHUNK = 64L * 1024
        private const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024

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
    }
}

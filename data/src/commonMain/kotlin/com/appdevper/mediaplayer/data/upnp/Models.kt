package com.appdevper.mediaplayer.data.upnp

import com.appdevper.mediaplayer.domain.model.MediaKind

object UpnpTypes {
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** Compares service/device types ignoring the version suffix. */
    fun sameType(a: String, b: String): Boolean = a.substringBeforeLast(':') == b.substringBeforeLast(':')
}

data class UpnpService(
    val serviceType: String,
    val serviceId: String,
    val controlUrl: String,
    val eventSubUrl: String,
    val scpdUrl: String,
)

data class UpnpDevice(
    val udn: String,
    val friendlyName: String,
    val deviceType: String,
    val manufacturer: String,
    val modelName: String,
    val location: String,
    val iconUrl: String?,
    val services: List<UpnpService>,
) {
    fun service(type: String): UpnpService? = services.firstOrNull { UpnpTypes.sameType(it.serviceType, type) }

    val isMediaServer: Boolean get() = service(UpnpTypes.CONTENT_DIRECTORY) != null
    val isRenderer: Boolean get() = service(UpnpTypes.AV_TRANSPORT) != null

    /** IP/host of the device, used to show a subtitle. */
    val host: String get() = location.substringAfter("://").substringBefore('/').substringBefore(':')
}

data class DidlResource(
    val uri: String,
    val protocolInfo: String,
    val size: Long?,
    val duration: String?,
    val resolution: String?,
) {
    /** The 3rd field of protocolInfo, e.g. `http-get:*:video/mp4:*` -> `video/mp4`. */
    val mimeType: String get() = protocolInfo.split(':').getOrNull(2).orEmpty()
}

/** A subtitle file: its URL and type (`srt`, `vtt`). */
data class DidlSubtitle(val uri: String, val type: String)

data class DidlObject(
    val id: String,
    val parentId: String,
    val title: String,
    val upnpClass: String,
    val isContainer: Boolean,
    val childCount: Int?,
    val artist: String?,
    val album: String?,
    val albumArtUri: String?,
    val resources: List<DidlResource>,
    /** Subtitle files for a video (res with a subtitle type, or Samsung's sec:CaptionInfoEx). */
    val subtitles: List<DidlSubtitle> = emptyList(),
) {
    val kind: MediaKind
        get() {
            val mime = resources.firstOrNull()?.mimeType.orEmpty()
            return when {
                isContainer -> MediaKind.FOLDER
                upnpClass.startsWith("object.item.videoItem") || mime.startsWith("video/") -> MediaKind.VIDEO
                upnpClass.startsWith("object.item.audioItem") || mime.startsWith("audio/") -> MediaKind.AUDIO
                upnpClass.startsWith("object.item.imageItem") || mime.startsWith("image/") -> MediaKind.IMAGE
                else -> MediaKind.OTHER
            }
        }

    /** The best resource for playback: the first one served over http-get. */
    val primaryResource: DidlResource?
        get() = resources.firstOrNull { it.protocolInfo.startsWith("http-get") } ?: resources.firstOrNull()

    val url: String? get() = primaryResource?.uri

    /** Thumbnail to show in lists: album art, or the image itself for images. */
    val thumbnailUrl: String? get() = albumArtUri ?: if (kind == MediaKind.IMAGE) url else null

    val durationMs: Long? get() = primaryResource?.duration?.let(::parseUpnpTime)?.takeIf { it > 0 }
}

data class BrowseResult(val objects: List<DidlObject>, val totalMatches: Int)

class UpnpException(message: String, val code: Int? = null) : Exception(message)

/** Parses `H+:MM:SS[.F+]` (UPnP duration / REL_TIME) to milliseconds. */
fun parseUpnpTime(value: String): Long? {
    val v = value.trim()
    if (v.isEmpty() || v == "NOT_IMPLEMENTED") return null
    val parts = v.split(':')
    if (parts.size != 3) return null
    val h = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull() ?: return null
    val secParts = parts[2].split('.')
    val s = secParts[0].toLongOrNull() ?: return null
    val frac = secParts.getOrNull(1)?.let { f ->
        // fraction may be "500" or "5/10" style; only support decimals
        f.takeWhile { it.isDigit() }.padEnd(3, '0').take(3).toLongOrNull()
    } ?: 0L
    return ((h * 3600 + m * 60 + s) * 1000) + frac
}

/** Formats milliseconds as `H:MM:SS` (for Seek) or with millis (for DIDL durations). */
fun formatUpnpTime(ms: Long, withMillis: Boolean = false): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val base = "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    return if (withMillis) "$base.${(ms % 1000).toString().padStart(3, '0')}" else base
}

/** Human readable `M:SS` / `H:MM:SS` for UI. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

/** Resolves a (possibly relative) URL from a device description against its base URL. */
fun resolveUrl(base: String, relative: String): String {
    val rel = relative.trim()
    if (rel.isEmpty()) return base
    if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
    val schemeEnd = base.indexOf("://")
    if (schemeEnd < 0) return rel
    val authorityEnd = base.indexOf('/', schemeEnd + 3).let { if (it < 0) base.length else it }
    val origin = base.substring(0, authorityEnd)
    if (rel.startsWith("/")) return origin + rel
    val path = base.substring(authorityEnd).substringBefore('?')
    val dir = path.substringBeforeLast('/', "")
    return "$origin$dir/$rel"
}

package app.devper.appplayer.data.mapper

import app.devper.appplayer.data.upnp.Didl
import app.devper.appplayer.data.upnp.DidlObject
import app.devper.appplayer.data.upnp.DidlResource
import app.devper.appplayer.data.upnp.DidlSubtitle
import app.devper.appplayer.data.upnp.TransportStatus
import app.devper.appplayer.data.upnp.UpnpDevice
import app.devper.appplayer.data.upnp.formatUpnpTime
import app.devper.appplayer.domain.model.MediaDevice
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.RendererStatus
import app.devper.appplayer.domain.model.Subtitle
import app.devper.appplayer.domain.subtitle.SubtitleFiles
import io.ktor.http.decodeURLPart
import app.devper.appplayer.domain.model.TransportState

internal fun UpnpDevice.toDomain() = MediaDevice(
    id = udn,
    name = friendlyName,
    manufacturer = manufacturer,
    model = modelName,
    host = host,
    iconUrl = iconUrl,
    isServer = isMediaServer,
    isRenderer = isRenderer,
)

internal fun DidlObject.toDomain() = MediaEntry(
    id = id,
    parentId = parentId,
    title = title,
    kind = kind,
    childCount = childCount,
    artist = artist,
    album = album,
    thumbnailUrl = thumbnailUrl,
    streamUrl = url,
    mimeType = primaryResource?.mimeType?.takeIf { it.isNotEmpty() },
    sizeBytes = primaryResource?.size,
    durationMs = durationMs,
    subtitles = subtitles.map { it.toDomain() },
)

/** Labelled by the language in its file name, else the file name itself. */
internal fun DidlSubtitle.toDomain(): Subtitle {
    val fileName = uri.substringBefore('?').substringAfterLast('/').decodeUrlPath()
    val language = SubtitleFiles.languageOf(fileName)
    return Subtitle(url = uri, label = SubtitleFiles.label(language, fileName), language = language)
}

private fun String.decodeUrlPath(): String = runCatching { decodeURLPart() }.getOrDefault(this)

/** Rebuilds DIDL-Lite metadata for SetAVTransportURI (some TVs refuse a URI without it). */
internal fun MediaEntry.toDidl(): DidlObject {
    val mime = mimeType ?: when (kind) {
        MediaKind.VIDEO -> "video/mp4"
        MediaKind.AUDIO -> "audio/mpeg"
        MediaKind.IMAGE -> "image/jpeg"
        else -> "application/octet-stream"
    }
    return DidlObject(
        id = id,
        parentId = parentId,
        title = title,
        upnpClass = when (kind) {
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
            MediaKind.IMAGE -> "object.item.imageItem.photo"
            MediaKind.FOLDER -> "object.container"
            MediaKind.OTHER -> "object.item"
        },
        isContainer = false,
        childCount = null,
        artist = artist,
        album = album,
        albumArtUri = thumbnailUrl,
        resources = listOfNotNull(
            streamUrl?.let {
                DidlResource(it, Didl.protocolInfo(mime), sizeBytes, durationMs?.let { d -> formatUpnpTime(d, withMillis = true) }, null)
            },
        ),
        // TVs that show subtitles (Samsung, LG…) find them here.
        subtitles = subtitles.map { DidlSubtitle(it.url, if (it.url.substringBefore('?').endsWith(".vtt", ignoreCase = true)) "vtt" else "srt") },
    )
}

internal fun TransportStatus.toDomain() = RendererStatus(
    state = when (state) {
        "PLAYING" -> TransportState.PLAYING
        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> TransportState.PAUSED
        "STOPPED" -> TransportState.STOPPED
        "TRANSITIONING" -> TransportState.TRANSITIONING
        "NO_MEDIA_PRESENT" -> TransportState.NO_MEDIA
        else -> TransportState.UNKNOWN
    },
    positionMs = positionMs,
    durationMs = durationMs,
)

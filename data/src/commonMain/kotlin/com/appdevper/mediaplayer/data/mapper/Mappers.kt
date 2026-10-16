package com.appdevper.mediaplayer.data.mapper

import com.appdevper.mediaplayer.data.upnp.Didl
import com.appdevper.mediaplayer.data.upnp.DidlObject
import com.appdevper.mediaplayer.data.upnp.DidlResource
import com.appdevper.mediaplayer.data.upnp.TransportStatus
import com.appdevper.mediaplayer.data.upnp.UpnpDevice
import com.appdevper.mediaplayer.data.upnp.formatUpnpTime
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState

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
)

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

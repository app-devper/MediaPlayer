package com.appdevper.mediaplayer.domain.model

enum class MediaKind { FOLDER, VIDEO, AUDIO, IMAGE, OTHER }

/** A device found on the local network (DLNA media server and/or renderer). */
data class MediaDevice(
    val id: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    val host: String,
    val iconUrl: String?,
    val isServer: Boolean,
    val isRenderer: Boolean,
)

/** A folder or a media file inside a media server. */
data class MediaEntry(
    val id: String,
    val parentId: String,
    val title: String,
    val kind: MediaKind,
    val childCount: Int? = null,
    val artist: String? = null,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val streamUrl: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
) {
    val isFolder: Boolean get() = kind == MediaKind.FOLDER
    val isPlayable: Boolean get() = streamUrl != null && kind in setOf(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE)
}

/** Where playback happens: on this device, or on a DLNA renderer (TV, speaker…). */
sealed interface PlaybackTarget {
    data object Local : PlaybackTarget
    data class Renderer(val device: MediaDevice) : PlaybackTarget
}

enum class TransportState { PLAYING, PAUSED, STOPPED, TRANSITIONING, NO_MEDIA, UNKNOWN }

data class RendererStatus(
    val state: TransportState,
    val positionMs: Long,
    val durationMs: Long,
)

data class RemotePlayback(
    val status: RendererStatus? = null,
    val volume: Int? = null,
    val error: String? = null,
)

data class PlaybackState(
    val target: PlaybackTarget = PlaybackTarget.Local,
    val queue: List<MediaEntry> = emptyList(),
    val index: Int = -1,
    val remote: RemotePlayback = RemotePlayback(),
) {
    val current: MediaEntry? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex
    val hasPrevious: Boolean get() = index > 0
}

data class ServerSettings(
    val name: String,
    val autoStart: Boolean,
    val shareVideo: Boolean,
    val shareAudio: Boolean,
    val shareImage: Boolean,
    val folders: List<String>,
) {
    val sharedKinds: Set<MediaKind>
        get() = buildSet {
            if (shareVideo) add(MediaKind.VIDEO)
            if (shareAudio) add(MediaKind.AUDIO)
            if (shareImage) add(MediaKind.IMAGE)
        }
}

/** What the built-in server can do on this platform. */
data class ServerCapabilities(
    val canChooseFolders: Boolean,
    val sourceDescription: String,
)

sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus
    data class Running(val name: String, val url: String, val counts: Map<MediaKind, Int>) : ServerStatus
    data class Failed(val message: String) : ServerStatus
}

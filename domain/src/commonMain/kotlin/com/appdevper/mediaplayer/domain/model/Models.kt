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
    /** Whether the local player starts as soon as the current item loads. */
    val autoPlay: Boolean = true,
    /** Last state reported by the local player, null until it reports. */
    val local: RendererStatus? = null,
    val localVolume: Int = 100,
    val localMuted: Boolean = false,
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
    /** Lets other devices play on this one (DLNA renderer). Off by default: anyone on the LAN could cast. */
    val receiverEnabled: Boolean = false,
    /** AirPlay receiver (screen mirroring + audio from iPhone/iPad/Mac). Off by default, like DLNA. */
    val airPlayEnabled: Boolean = false,
    /** A sender's first AirPlay connection needs the code shown on this screen. */
    val airPlayRequirePin: Boolean = true,
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

/** Commands for the on-screen player that don't come from its own controls (e.g. a DLNA controller). */
sealed interface LocalPlayerCommand {
    data object Play : LocalPlayerCommand
    data object Pause : LocalPlayerCommand
    /** Pause and rewind; the player reports [TransportState.STOPPED] until it plays again. */
    data object Stop : LocalPlayerCommand
    data class Seek(val positionMs: Long) : LocalPlayerCommand
}

/** What another device asks this one to do while it acts as a receiver. */
sealed interface CastCommand {
    data class Load(val entry: MediaEntry, val autoPlay: Boolean) : CastCommand
    data object Play : CastCommand
    data object Pause : CastCommand
    data object Stop : CastCommand
    data class Seek(val positionMs: Long) : CastCommand
    data class SetVolume(val volume: Int) : CastCommand
    data class SetMute(val muted: Boolean) : CastCommand
}

/** Local playback as reported back to the sending device. [status] is null when nothing is loaded. */
data class ReceiverPlayback(
    val entry: MediaEntry? = null,
    val status: RendererStatus? = null,
    val volume: Int = 100,
    val muted: Boolean = false,
)

sealed interface ReceiverStatus {
    data object Stopped : ReceiverStatus
    data class Running(val name: String) : ReceiverStatus
    data class Failed(val message: String) : ReceiverStatus
}

sealed interface AirPlayStatus {
    /** This platform can't receive AirPlay (iOS). */
    data object Unsupported : AirPlayStatus
    data object Stopped : AirPlayStatus
    data class Running(val name: String) : AirPlayStatus
    data class Failed(val message: String) : AirPlayStatus
}

/** What an AirPlay sender is doing right now. */
data class AirPlayActivity(
    /** Sender's name while one is connected. */
    val client: String? = null,
    val mirroring: Boolean = false,
    /** Audio is playing without a mirrored screen (music, podcasts). */
    val audioOnly: Boolean = false,
    /** Code to show while a new sender pairs. */
    val pin: String? = null,
)

package app.devper.appplayer.domain.model

// Media on a server, and the devices on the network.

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
    /** Subtitle files that go with a video (sidecar .srt / .vtt). */
    val subtitles: List<Subtitle> = emptyList(),
) {
    val isFolder: Boolean get() = kind == MediaKind.FOLDER
    val isPlayable: Boolean get() = streamUrl != null && kind in setOf(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE)
}

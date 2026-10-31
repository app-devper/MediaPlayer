package app.devper.appplayer.domain.model

// The built-in DLNA media server.

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

/** Counts per kind for the server summary line. */
fun Map<MediaKind, Int>.countOf(kind: MediaKind): Int = this[kind] ?: 0

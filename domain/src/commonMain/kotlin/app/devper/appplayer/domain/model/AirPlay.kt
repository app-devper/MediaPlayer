package app.devper.appplayer.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

// The AirPlay receiver and what a sender is doing.

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
    /** The song an audio-only sender is playing (music apps), once it says. */
    val nowPlaying: AirPlayNowPlaying? = null,
    /** The sender takes remote-control commands ([SenderCommand]): next, previous, play/pause. */
    val canControlSender: Boolean = false,
    /** The local player's entry for the sender's AirPlay video, while it's current. */
    val videoEntryId: String? = null,
)

/** What a sender's own controls do in its now-playing app (Music, YouTube, YouTube Music…). */
enum class SenderCommand { Next, Previous, PlayPause }

/**
 * The AirPlay sender as playback sees it: what it's doing, and its own controls. The playback
 * session sends next, previous and play/pause there while the sender decides what plays.
 */
interface SenderControl {
    val activity: StateFlow<AirPlayActivity>

    /** Asks the sender to skip or play/pause ([AirPlayActivity.canControlSender]); false if it didn't. */
    suspend fun sendToSender(command: SenderCommand): Boolean

    /** No AirPlay (iOS, tests). */
    object None : SenderControl {
        override val activity = MutableStateFlow(AirPlayActivity())
        override suspend fun sendToSender(command: SenderCommand) = false
    }
}

/** Song details, cover art and progress an AirPlay audio sender reports. */
data class AirPlayNowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** JPEG/PNG cover art. */
    val artwork: ByteArray? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** When [positionMs] was reported; the position advances from here while playing. */
    val reportedAt: TimeSource.Monotonic.ValueTimeMark? = null,
) {
    fun positionNow(): Long =
        (positionMs + (reportedAt?.elapsedNow()?.inWholeMilliseconds ?: 0)).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }

    // ByteArray: compare the cover art by content, so an identical report isn't "new".
    override fun equals(other: Any?): Boolean = other is AirPlayNowPlaying && title == other.title && artist == other.artist &&
        album == other.album && artwork.contentEquals(other.artwork) && positionMs == other.positionMs &&
        durationMs == other.durationMs && reportedAt == other.reportedAt

    override fun hashCode(): Int = listOf(title, artist, album, artwork?.contentHashCode(), positionMs, durationMs).hashCode()
}

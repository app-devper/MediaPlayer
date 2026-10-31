package app.devper.appplayer.data.repository

import app.devper.appplayer.airplay.AirPlayVideoControl
import app.devper.appplayer.airplay.AirPlayVideoInfo
import app.devper.appplayer.domain.model.CastCommand
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.ReceiverLink
import app.devper.appplayer.domain.model.ReceiverPlayback
import app.devper.appplayer.domain.model.RendererStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * A sender's AirPlay video (YouTube, Safari…) on the local player: /play, /rate, /scrub and /stop
 * become commands on the [link], and the player's progress goes back to the sender ([info]).
 * Called from the receiver's native threads and the app's.
 */
internal class AirPlayVideoSession(
    private val scope: CoroutineScope,
    /**
     * A video that ended and that its sender never stopped or replaced is closed after this long. Long:
     * YouTube waits ~20 s on its end screen before the next video, and the user may seek back meanwhile.
     */
    private val abandonedEndMs: Long,
    /** The sender's name, for the video's title. */
    private val sender: () -> String?,
    /** The video's entry id when one starts, null when it stops. */
    private val onVideo: (String?) -> Unit,
) : AirPlayVideoControl {

    /** The playback session's line; nothing plays before it's set. */
    @Volatile var link: ReceiverLink? = null

    @Volatile private var playback = ReceiverPlayback()
    /** Entry id of the current video; null when none. */
    @Volatile private var videoId: String? = null
    private var sequence = 0
    @Volatile private var endJob: Job? = null

    // The sender's volume (its volume buttons) as 0..100, applied to its videos.
    @Volatile private var senderVolume: Int? = null
    // Our player's volume before a video changed it, restored when the video stops.
    @Volatile private var volumeBeforeVideo: Int? = null

    private fun send(command: CastCommand) = link?.send(command) == true

    // The sender's video is what this device plays: not something picked or cast since.
    private val owned: Boolean get() = videoId != null && link?.owns == true

    /** The sender's volume buttons, or null once it's gone. */
    fun setSenderVolume(volume: Int?) {
        senderVolume = volume
        if (volume != null && videoId != null) applyVolume(volume)
    }

    private fun applyVolume(volume: Int) {
        if (volumeBeforeVideo == null) volumeBeforeVideo = playback.volume
        send(CastCommand.SetVolume(volume))
    }

    /** What the local player reports. */
    fun publish(playback: ReceiverPlayback) {
        this.playback = playback
        val id = videoId
        // Played to the end: the sender normally stops or plays the next video. If it never does
        // (phone locked, app killed), end the session here so a later /rate can't restart it.
        // Anything else (still playing, seeked back, something else plays now) cancels that.
        if (owned && playback.entry?.id == id && playback.status.isFinished()) {
            if (endJob == null) endJob = scope.launch {
                delay(abandonedEndMs)
                if (videoId == id && owned) stop()
            }
        } else {
            endJob?.cancel()
            endJob = null
        }
    }

    /** The player reached the end; leaving the player or a controller's Stop isn't an end. */
    private fun RendererStatus?.isFinished() = this != null && ended && durationMs > 0

    override fun play(url: String, startSeconds: Float) {
        // YouTube's next video comes at the same local URL: a new id makes the player load it.
        val id = "airplay-${++sequence}"
        endJob?.cancel()
        endJob = null
        onVideo(id)
        val entry = MediaEntry(
            id = id,
            parentId = "-1",
            title = sender()?.let { "AirPlay from $it" } ?: "AirPlay video",
            kind = MediaKind.VIDEO,
            streamUrl = url,
            mimeType = if (".m3u8" in url) "application/x-mpegURL" else null,
        )
        send(CastCommand.Load(entry, autoPlay = true, startPositionMs = (startSeconds * 1000).toLong().coerceAtLeast(0)))
        // Its id once the load is in: before, info() would see something else here and drop the sender.
        videoId = id
        senderVolume?.let(::applyVolume)
    }

    // Only while its video is here: after /stop, YouTube sends /rate 1 before the next /play,
    // which would otherwise resume the stopped one.
    override fun seek(seconds: Float) {
        if (owned) send(CastCommand.Seek((seconds * 1000).toLong()))
    }

    override fun setRate(rate: Float) {
        if (owned) send(if (rate > 0f) CastCommand.Play else CastCommand.Pause)
    }

    override fun stop() {
        if (videoId == null) return
        videoId = null
        onVideo(null)
        endJob?.cancel()
        endJob = null
        send(CastCommand.Stop)
        volumeBeforeVideo?.let { send(CastCommand.SetVolume(it)) }
        volumeBeforeVideo = null
    }

    override fun info(): AirPlayVideoInfo? {
        val id = videoId ?: return null
        // Something else plays here now: tell the sender its video is over. Deliberately -1: UxPlay
        // then drops the sender's connection (YouTube shows an error), since the user replaced its
        // video on this device.
        if (link?.owns != true) return if (link != null) AirPlayVideoInfo(-1.0, 0.0, playing = false) else null
        val p = playback
        // Not reported yet (the player is loading it).
        if (p.entry?.id != id) return null
        val status = p.status ?: return null
        // At the end: report the end, paused, as an Apple TV does; the sender then plays the next
        // video or stops. (Duration -1, UxPlay's "finished", drops the sender's connection.)
        // The last reported position can be seconds short of the container duration.
        val finished = status.isFinished()
        return AirPlayVideoInfo(
            durationSeconds = status.durationMs / 1000.0,
            positionSeconds = (if (finished) status.durationMs else status.positionMs) / 1000.0,
            playing = status.state.playing,
        )
    }
}

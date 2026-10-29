package com.appdevper.mediaplayer.playback

/**
 * What [FfmpegPlayer]'s frames are timed against: the audio being heard; when there's none (after a
 * seek, or it ran out), the wall clock, which runs on from the seek target or from the audio's end.
 * Audio counts as run out when nothing is heard and nothing was written for [audioRanOutMs] (audio
 * shorter than the video); a shorter gap only holds the clock, so it stays in step when audio comes
 * back. No threads of its own: the player's call it, and [nowNs] is its time (tests pass their own).
 */
internal class PlaybackClock(
    paused: Boolean,
    private val nowNs: () -> Long = System::nanoTime,
    private val audioRanOutMs: Long = 500,
) {
    private var paused = paused
    // pts at the end of what was written to the output; -1 for none since a seek.
    private var audioEndMs = -1L
    private var audioWrittenAtNs = 0L
    private var audioRanOut = false
    private var wallBaseMs = 0L
    private var wallStartNs = nowNs()

    @Synchronized fun play() {
        paused = false
        wallStartNs = nowNs()
    }

    /** Holds the running clock (not a shown seek target), so frames stay in step after [play]. */
    @Synchronized fun pause(bufferedMs: () -> Long, hasAudio: Boolean) {
        wallBaseMs = ms(bufferedMs, hasAudio)
        paused = true
    }

    /** Until audio from after it is heard, the wall clock runs from [targetMs]. */
    @Synchronized fun seek(targetMs: Long) {
        audioEndMs = -1
        audioRanOut = false
        wallBaseMs = targetMs
        wallStartNs = nowNs()
    }

    /** What was written is gone (the output was flushed for a seek). */
    @Synchronized fun audioDropped() {
        audioEndMs = -1
    }

    /** [durationMs] of audio at [ptsMs] (negative: right after the last) went to the output. */
    @Synchronized fun audioWritten(ptsMs: Long, durationMs: Long) {
        audioEndMs = (if (ptsMs >= 0) ptsMs else maxOf(audioEndMs, 0)) + durationMs
        audioWrittenAtNs = nowNs()
        audioRanOut = false
    }

    /** Audio is what's advancing the clock (it's being heard), as opposed to the wall clock. */
    @Synchronized fun audioIsClock(hasAudio: Boolean): Boolean = hasAudio && audioEndMs >= 0 && !audioRanOut

    /** Now, with [bufferedMs] of the written audio not heard yet (read here, in step with what was written). */
    @Synchronized fun ms(bufferedMs: () -> Long, hasAudio: Boolean): Long {
        if (!audioIsClock(hasAudio)) return wallMs()
        val buffered = bufferedMs()
        if (buffered <= 0 && !paused && nowNs() - audioWrittenAtNs > audioRanOutMs * 1_000_000) {
            wallBaseMs = audioEndMs
            wallStartNs = nowNs()
            audioRanOut = true
            return wallMs()
        }
        return (audioEndMs - buffered).coerceAtLeast(0)
    }

    private fun wallMs(): Long = if (paused) wallBaseMs else wallBaseMs + (nowNs() - wallStartNs) / 1_000_000
}

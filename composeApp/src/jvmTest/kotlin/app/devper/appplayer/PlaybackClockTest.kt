package app.devper.appplayer

import app.devper.appplayer.playback.PlaybackClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The desktop player's clock, in made-up time: the rules FfmpegPlayerTest only reached in real time. */
class PlaybackClockTest {
    private var nowMs = 0L
    private val clock = PlaybackClock(paused = false, nowNs = { nowMs * 1_000_000 }, audioRanOutMs = 500)

    @Test
    fun withoutAudioTheWallClockRunsAndHoldsWhilePaused() {
        nowMs = 1_000
        clock.seek(10_000)
        nowMs = 1_400
        assertEquals(10_400, clock.ms({ 0 }, hasAudio = false))
        clock.pause({ 0 }, hasAudio = false)
        nowMs = 9_000
        assertEquals(10_400, clock.ms({ 0 }, hasAudio = false))
        clock.play()
        nowMs = 9_100
        assertEquals(10_500, clock.ms({ 0 }, hasAudio = false))
    }

    @Test
    fun theAudioHeardIsTheClockOnceItIsWritten() {
        clock.seek(5_000)
        assertFalse(clock.audioIsClock(hasAudio = true)) // nothing from after the seek yet
        clock.audioWritten(ptsMs = 5_000, durationMs = 100)
        clock.audioWritten(ptsMs = -1, durationMs = 100) // no pts: right after the last
        assertTrue(clock.audioIsClock(hasAudio = true))
        assertEquals(5_150, clock.ms({ 50 }, hasAudio = true)) // written minus not heard yet
        clock.audioDropped()
        assertFalse(clock.audioIsClock(hasAudio = true))
    }

    @Test
    fun audioThatRanOutHandsOverToTheWallClockFromItsEnd() {
        clock.seek(0)
        clock.audioWritten(ptsMs = 0, durationMs = 2_000)
        nowMs = 300 // a short gap: held, so it stays in step
        assertEquals(2_000, clock.ms({ 0 }, hasAudio = true))
        nowMs = 600 // ran out (shorter than the video): runs on from its end
        assertEquals(2_000, clock.ms({ 0 }, hasAudio = true))
        nowMs = 1_600
        assertEquals(3_000, clock.ms({ 0 }, hasAudio = true))
        assertFalse(clock.audioIsClock(hasAudio = true))

        // A pause after it ran out resumes where it was, not counting the pause.
        clock.pause({ 0 }, hasAudio = true)
        nowMs = 5_000
        clock.play()
        nowMs = 5_500
        assertEquals(3_500, clock.ms({ 0 }, hasAudio = true))

        // Paused audio isn't "run out", however long it's paused.
        clock.seek(0)
        clock.audioWritten(0, 1_000)
        clock.pause({ 0 }, hasAudio = true)
        nowMs = 60_000
        assertTrue(clock.audioIsClock(hasAudio = true).also { clock.ms({ 0 }, hasAudio = true) })
        assertEquals(1_000, clock.ms({ 0 }, hasAudio = true))
    }
}

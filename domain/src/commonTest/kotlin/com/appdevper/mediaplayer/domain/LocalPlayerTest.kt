package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val song = MediaEntry("a1", "0", "Song", MediaKind.AUDIO, streamUrl = "http://h/a1.mp3")
private val movie = MediaEntry("v1", "0", "Movie", MediaKind.VIDEO, streamUrl = "http://h/v1.mp4")
private val photo = MediaEntry("p1", "0", "Photo", MediaKind.IMAGE, streamUrl = "http://h/p1.jpg")

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerTest {

    private fun TestScope.player(engine: FakeEngine) = LocalPlayer(engine, backgroundScope, pollIntervalMs = 100)

    @Test
    fun loadedPausedReportsStoppedUntilItPlays() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(song, autoPlay = false, startPositionMs = 12_000)
        runCurrent()
        assertEquals(listOf("load:a1@12000"), engine.calls)
        assertEquals(TransportState.STOPPED, player.status.value?.state)
        assertEquals(song, player.loaded.value)

        player.play()
        runCurrent()
        assertEquals(TransportState.PLAYING, player.status.value?.state)
        player.pause()
        runCurrent()
        assertEquals(TransportState.PAUSED, player.status.value?.state)

        player.stop()
        runCurrent()
        assertEquals(TransportState.STOPPED, player.status.value?.state)
        assertEquals(0, player.status.value?.positionMs)
    }

    @Test
    fun theEndIsReportedAndPlayStartsOver() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(song, autoPlay = true, startPositionMs = 0)
        runCurrent()
        engine.finish()
        advanceTimeBy(150) // the next poll sees it
        assertEquals(TransportState.STOPPED, player.status.value?.state)
        assertTrue(player.status.value!!.ended)

        engine.calls.clear()
        player.play()
        runCurrent()
        assertEquals(listOf("seek:0", "play"), engine.calls)
        assertEquals(TransportState.PLAYING, player.status.value?.state)
    }

    @Test
    fun seekingAfterTheEndPlaysOnFromThere() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(song, autoPlay = true, startPositionMs = 0)
        runCurrent()
        engine.finish()
        advanceTimeBy(150)
        player.seek(30_000) // dragging the bar
        runCurrent()
        assertEquals(TransportState.PAUSED, player.status.value?.state) // no longer stopped
        engine.calls.clear()
        player.play()
        runCurrent()
        assertEquals(listOf("play"), engine.calls)
    }

    @Test
    fun playAfterAnErrorLoadsItAgainWhereItWas() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(movie, autoPlay = true, startPositionMs = 0)
        runCurrent()
        engine.facts = engine.facts.copy(positionMs = 42_000, error = "Network lost")
        advanceTimeBy(150)
        assertEquals("Network lost", player.error.value)
        assertEquals(TransportState.PAUSED, player.status.value?.state)

        engine.calls.clear()
        player.play()
        runCurrent()
        assertEquals(listOf("reload:42000", "play"), engine.calls)
        assertNull(player.error.value)
    }

    @Test
    fun startedFromTheSystemIsNoLongerStopped() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(song, autoPlay = false, startPositionMs = 0)
        runCurrent()
        engine.facts = engine.facts.copy(playing = true) // e.g. resumed after a phone call
        advanceTimeBy(150)
        engine.facts = engine.facts.copy(playing = false) // then a headset pauses it
        advanceTimeBy(100)
        assertEquals(TransportState.PAUSED, player.status.value?.state)
    }

    @Test
    fun aVideoPausesInTheBackgroundAndPlaysOnWhenBack() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(movie, autoPlay = true, startPositionMs = 0)
        player.onAppBackground()
        runCurrent()
        assertEquals(TransportState.PAUSED, player.status.value?.state)
        player.onAppForeground()
        runCurrent()
        assertEquals(TransportState.PLAYING, player.status.value?.state)

        // Paused by anyone meanwhile: it stays paused when the app is back.
        player.onAppBackground()
        player.pause()
        player.onAppForeground()
        runCurrent()
        assertEquals(TransportState.PAUSED, player.status.value?.state)

        // On another screen (Picture in Picture, a TV) it plays on.
        player.play()
        engine.facts = engine.facts.copy(shownElsewhere = true)
        player.onAppBackground()
        runCurrent()
        assertEquals(TransportState.PLAYING, player.status.value?.state)
    }

    @Test
    fun subtitleTracksInTheFileComeFromTheEngine() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(movie, autoPlay = true, startPositionMs = 0)
        runCurrent()
        val thai = TextTrack("2:0", "Thai", "th")
        engine.facts = engine.facts.copy(textTracks = listOf(thai))
        advanceTimeBy(150)
        assertEquals(listOf(thai), player.textTracks.value)

        player.selectTextTrack(thai.id)
        engine.facts = engine.facts.copy(subtitleText = "สวัสดี")
        advanceTimeBy(150)
        assertEquals("track:2:0", engine.calls.last())
        assertEquals("สวัสดี", player.subtitleText.value)

        player.load(photo, autoPlay = true, startPositionMs = 0) // nothing loaded: none
        runCurrent()
        assertEquals(emptyList(), player.textTracks.value)
        assertNull(player.subtitleText.value)
    }

    @Test
    fun songsPlayOnInTheBackground() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.load(song, autoPlay = true, startPositionMs = 0)
        player.onAppBackground()
        runCurrent()
        assertEquals(TransportState.PLAYING, player.status.value?.state)
    }

    @Test
    fun aPhotoEmptiesThePlayerAndTheLastReportSaysStopped() = runTest {
        val engine = FakeEngine()
        val player = player(engine)
        player.setVolume(0.4f)
        player.load(song, autoPlay = true, startPositionMs = 0)
        runCurrent()
        assertEquals(0.4f, engine.gain)
        player.load(photo, autoPlay = true, startPositionMs = 0)
        runCurrent()
        assertNull(player.loaded.value)
        assertEquals(TransportState.STOPPED, player.status.value?.state)
        assertEquals("unload", engine.calls.last())

        // Nothing loaded: commands do nothing.
        engine.calls.clear()
        player.play()
        player.seek(5_000)
        runCurrent()
        assertEquals(emptyList(), engine.calls)
    }
}

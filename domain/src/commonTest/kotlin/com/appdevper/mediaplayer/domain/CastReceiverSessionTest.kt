package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.testing.FakeEngine
import com.appdevper.mediaplayer.testing.FakeReceiver
import com.appdevper.mediaplayer.testing.FakeRenderer
import com.appdevper.mediaplayer.testing.playbackSession
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.CastReceiverSession
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ReceiverSettings(enabled: Boolean) : SettingsRepository {
    override val serverSettings = MutableStateFlow(ServerSettings("Phone", false, true, true, true, emptyList(), receiverEnabled = enabled))
    override fun update(transform: (ServerSettings) -> ServerSettings) = serverSettings.update(transform)
}

private val clip = MediaEntry("cast", "-1", "Holiday", MediaKind.VIDEO, streamUrl = "http://10.0.0.9/holiday.mp4")
private val photo = MediaEntry("cast", "-1", "Beach", MediaKind.IMAGE, streamUrl = "http://10.0.0.9/beach.jpg")

@OptIn(ExperimentalCoroutinesApi::class)
class CastReceiverSessionTest {

    @Test
    fun followsSettings() = runTest {
        val receiver = FakeReceiver()
        val settings = ReceiverSettings(enabled = false)
        CastReceiverSession(receiver, settings, playbackSession(FakeRenderer(volume = 50), backgroundScope), backgroundScope).start()
        runCurrent()
        assertEquals(listOf("stop"), receiver.calls)

        settings.update { it.copy(receiverEnabled = true) }
        runCurrent()
        settings.update { it.copy(name = "Kitchen") }
        runCurrent()
        settings.update { it.copy(shareVideo = false) } // unrelated: no restart
        runCurrent()
        settings.update { it.copy(receiverEnabled = false) }
        runCurrent()
        assertEquals(listOf("stop", "start:Phone", "start:Kitchen", "stop"), receiver.calls)
    }

    @Test
    fun loadPlaysHereAndOpensThePlayer() = runTest {
        val receiver = FakeReceiver()
        val renderer = FakeRenderer(volume = 50)
        val engine = FakeEngine()
        val playback = playbackSession(renderer, backgroundScope, engine)
        val session = CastReceiverSession(receiver, ReceiverSettings(enabled = true), playback, backgroundScope)
        playback.setTarget(PlaybackTarget.Renderer(MediaDevice("uuid:tv", "TV", "", "", "h", null, false, true)))
        session.start()
        val opened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()

        receiver.commands.emit(CastCommand.Load(clip, autoPlay = false))
        receiver.commands.emit(CastCommand.Play)
        receiver.commands.emit(CastCommand.Seek(42_000))
        receiver.commands.emit(CastCommand.SetVolume(30))
        receiver.commands.emit(CastCommand.SetMute(true))
        receiver.commands.emit(CastCommand.Stop)
        runCurrent()

        val s = playback.state.value
        assertEquals(PlaybackTarget.Local, s.target)
        assertEquals(listOf(clip), s.queue)
        assertEquals(false, s.autoPlay)
        assertEquals(30, s.localVolume)
        assertTrue(s.localMuted)
        assertEquals(0f, engine.gain)
        assertTrue(opened.isCompleted)
        val reopened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()
        receiver.commands.emit(CastCommand.Play) // player closed meanwhile → Play brings it back
        runCurrent()
        assertTrue(reopened.isCompleted)
        assertTrue("stop" in renderer.calls) // the TV we were casting to is released
        // Stop = pause and rewind.
        assertEquals(listOf("load:cast@0", "play", "seek:42000", "pause", "seek:0", "play"), engine.calls)
    }

    @Test
    fun afterTheUserPicksARendererTheSenderNoLongerDrivesIt() = runTest {
        val receiver = FakeReceiver()
        val renderer = FakeRenderer(volume = 50)
        val engine = FakeEngine()
        val playback = playbackSession(renderer, backgroundScope, engine)
        CastReceiverSession(receiver, ReceiverSettings(enabled = true), playback, backgroundScope).start()
        runCurrent()
        receiver.commands.emit(CastCommand.Load(clip, autoPlay = true))
        runCurrent()
        playback.setTarget(PlaybackTarget.Renderer(MediaDevice("uuid:tv", "TV", "", "", "h", null, false, true)))
        runCurrent()
        engine.calls.clear()
        receiver.commands.emit(CastCommand.Pause)
        receiver.commands.emit(CastCommand.SetVolume(5))
        runCurrent()
        assertEquals(emptyList(), engine.calls)
        assertEquals(50, playback.state.value.remote.volume) // the TV's own volume, untouched
        assertNull(receiver.published.last().entry) // and the sender is told nothing plays here
    }

    @Test
    fun publishesWhatThePlayerReports() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngine()
        val playback = playbackSession(FakeRenderer(volume = 50), backgroundScope, engine)
        CastReceiverSession(receiver, ReceiverSettings(enabled = true), playback, backgroundScope).start()
        runCurrent()
        assertNull(receiver.published.last().entry)

        receiver.commands.emit(CastCommand.Load(clip, autoPlay = true))
        receiver.commands.emit(CastCommand.SetVolume(70))
        runCurrent()
        engine.facts = engine.facts.copy(positionMs = 5_000)
        advanceTimeBy(300) // the player's next poll
        runCurrent()
        assertEquals(ReceiverPlayback(clip, RendererStatus(TransportState.PLAYING, 5_000, 60_000), 70, false), receiver.published.last())

        // Photos have no player behind them: shown means playing.
        receiver.commands.emit(CastCommand.Load(photo, autoPlay = true))
        runCurrent()
        assertEquals(RendererStatus(TransportState.PLAYING, 0, 0), receiver.published.last().status)
    }
}

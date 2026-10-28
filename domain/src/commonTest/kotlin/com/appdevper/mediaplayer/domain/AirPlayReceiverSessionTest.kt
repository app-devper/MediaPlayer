package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeAirPlay(supported: Boolean = true) : AirPlayRepository {
    val calls = mutableListOf<String>()
    override val status = MutableStateFlow(if (supported) AirPlayStatus.Stopped else AirPlayStatus.Unsupported)
    override val activity = MutableStateFlow(AirPlayActivity())
    override val commands = MutableSharedFlow<CastCommand>()
    val published = mutableListOf<ReceiverPlayback>()
    override fun publish(playback: ReceiverPlayback) { published += playback }
    val sentToSender = mutableListOf<SenderCommand>()
    override suspend fun sendToSender(command: SenderCommand): Boolean { sentToSender += command; return true }
    override suspend fun start(name: String, requirePin: Boolean) {
        calls += "start:$name:pin=$requirePin"
        status.value = AirPlayStatus.Running(name)
    }
    override suspend fun stop() {
        calls += "stop"
        if (status.value != AirPlayStatus.Unsupported) status.value = AirPlayStatus.Stopped
    }
}

private class AirPlaySettings(enabled: Boolean) : SettingsRepository {
    override val serverSettings = MutableStateFlow(ServerSettings("Living Room", false, true, true, true, emptyList(), airPlayEnabled = enabled))
    override fun update(transform: (ServerSettings) -> ServerSettings) = serverSettings.update(transform)
}

private class SilentRenderer : RendererRepository {
    override suspend fun load(device: MediaDevice, entry: MediaEntry) {}
    override suspend fun play(device: MediaDevice) {}
    override suspend fun pause(device: MediaDevice) {}
    override suspend fun stop(device: MediaDevice) {}
    override suspend fun seek(device: MediaDevice, positionMs: Long) {}
    override suspend fun status(device: MediaDevice) = RendererStatus(TransportState.STOPPED, 0, 0)
    override suspend fun volume(device: MediaDevice) = 50
    override suspend fun setVolume(device: MediaDevice, volume: Int) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class AirPlayReceiverSessionTest {

    @Test
    fun followsSettings() = runTest {
        val airPlay = FakeAirPlay()
        val settings = AirPlaySettings(enabled = false)
        AirPlayReceiverSession(airPlay, settings, playbackSession(SilentRenderer(), backgroundScope), backgroundScope).start()
        runCurrent()
        settings.update { it.copy(airPlayEnabled = true) }
        runCurrent()
        settings.update { it.copy(airPlayRequirePin = false) }
        runCurrent()
        settings.update { it.copy(name = "Kitchen") }
        runCurrent()
        settings.update { it.copy(receiverEnabled = true, shareVideo = false) } // unrelated
        runCurrent()
        settings.update { it.copy(airPlayEnabled = false) }
        runCurrent()
        assertEquals(
            listOf("stop", "start:Living Room:pin=true", "start:Living Room:pin=false", "start:Kitchen:pin=false", "stop"),
            airPlay.calls,
        )
    }

    @Test
    fun unsupportedPlatformsNeverStart() = runTest {
        val airPlay = FakeAirPlay(supported = false)
        AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playbackSession(SilentRenderer(), backgroundScope), backgroundScope).start()
        runCurrent()
        assertTrue(airPlay.calls.none { it.startsWith("start") })
    }

    @Test
    fun mirroringOpensTheScreenAndPausesOurPlayer() = runTest {
        val airPlay = FakeAirPlay()
        val engine = FakeEngine()
        val playback = playbackSession(SilentRenderer(), backgroundScope, engine)
        playback.play(MediaEntry("v", "0", "Movie", MediaKind.VIDEO, streamUrl = "http://h/v.mp4"), emptyList())
        val session = AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope)
        session.start()
        val opened = backgroundScope.launch { session.openMirror.first() }
        runCurrent()
        assertFalse(opened.isCompleted)

        airPlay.activity.value = AirPlayActivity(client = "Worawit's iPhone", mirroring = true)
        runCurrent()
        assertTrue(opened.isCompleted)
        assertEquals(TransportState.PAUSED, playback.state.value.local?.state)

        // Still mirroring (e.g. the PIN went away): no second open.
        val again = backgroundScope.launch { session.openMirror.first() }
        airPlay.activity.value = AirPlayActivity(client = "Worawit's iPhone", mirroring = true, pin = null)
        runCurrent()
        assertFalse(again.isCompleted)
        again.cancel()
    }

    @Test
    fun airPlayVideoPlaysOnOurPlayerAndReportsBack() = runTest {
        val airPlay = FakeAirPlay()
        val engine = FakeEngine()
        val playback = playbackSession(SilentRenderer(), backgroundScope, engine)
        val session = AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope)
        session.start()
        val opened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()

        val video = MediaEntry("v", "-1", "YouTube", MediaKind.VIDEO, streamUrl = "http://localhost:7000/master.m3u8")
        airPlay.commands.emit(CastCommand.Load(video, autoPlay = true))
        airPlay.commands.emit(CastCommand.Seek(30_000))
        airPlay.commands.emit(CastCommand.Pause)
        runCurrent()
        assertEquals(listOf(video), playback.state.value.queue)
        assertTrue(opened.isCompleted)
        assertEquals(listOf("load:v@0", "seek:30000", "pause"), engine.calls)
        assertEquals(ReceiverPlayback(video, RendererStatus(TransportState.PAUSED, 30_000, 60_000)), airPlay.published.last())

        // Stopped by the sender: shown again later (after AirPlay audio), it must not start by itself.
        airPlay.commands.emit(CastCommand.Stop)
        runCurrent()
        assertEquals(false, playback.state.value.autoPlay)

        // A sender resuming at a position: the player starts there (no separate seek to race with the old player).
        airPlay.commands.emit(CastCommand.Load(video.copy(id = "v2"), autoPlay = true, startPositionMs = 157_000))
        runCurrent()
        assertEquals(157_000, playback.state.value.startPositionMs)
        assertEquals(true, playback.state.value.autoPlay)
        assertEquals("load:v2@157000", engine.calls.last())
    }

    @Test
    fun theSendersQueueDecidesNextAndPreviousForItsVideo() = runTest {
        val airPlay = FakeAirPlay()
        val engine = FakeEngine()
        val playback = playbackSession(SilentRenderer(), backgroundScope, engine)
        AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope).start()
        runCurrent()
        val video = MediaEntry("yt", "-1", "YouTube", MediaKind.VIDEO, streamUrl = "http://localhost:7000/master.m3u8")
        airPlay.commands.emit(CastCommand.Load(video, autoPlay = true))
        airPlay.activity.value = AirPlayActivity(client = "iPhone", canControlSender = true, videoEntryId = "yt")
        runCurrent()
        assertTrue(playback.next())
        assertTrue(playback.previous())
        runCurrent()
        assertEquals(listOf(SenderCommand.Next, SenderCommand.Previous), airPlay.sentToSender)

        // At the end of its video, the sender (not us) decides what comes next.
        engine.finish()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(SenderCommand.Next, SenderCommand.Previous), airPlay.sentToSender)
    }

    @Test
    fun audioFromASenderAlsoPausesOurPlayer() = runTest {
        val airPlay = FakeAirPlay()
        val playback = playbackSession(SilentRenderer(), backgroundScope)
        playback.play(MediaEntry("a", "0", "Song", MediaKind.AUDIO, streamUrl = "http://h/a.mp3"), emptyList())
        val session = AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope)
        session.start()
        runCurrent()
        val opened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()
        airPlay.activity.value = AirPlayActivity(client = "Mac", audioOnly = true)
        runCurrent()
        assertEquals(TransportState.PAUSED, playback.state.value.local?.state)
        assertTrue(opened.isCompleted, "music from a sender opens the player screen")
    }
}

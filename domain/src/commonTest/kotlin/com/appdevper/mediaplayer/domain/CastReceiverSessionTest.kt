package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeReceiver : CastReceiverRepository {
    val calls = mutableListOf<String>()
    val published = mutableListOf<ReceiverPlayback>()
    override val status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Stopped)
    override val commands = MutableSharedFlow<CastCommand>()
    override suspend fun start(name: String) { calls += "start:$name"; status.value = ReceiverStatus.Running(name) }
    override suspend fun stop() { calls += "stop"; status.value = ReceiverStatus.Stopped }
    override fun publish(playback: ReceiverPlayback) { published += playback }
}

private class NoRenderer : RendererRepository {
    val calls = mutableListOf<String>()
    override suspend fun load(device: MediaDevice, entry: MediaEntry) {}
    override suspend fun play(device: MediaDevice) {}
    override suspend fun pause(device: MediaDevice) {}
    override suspend fun stop(device: MediaDevice) { calls += "stop" }
    override suspend fun seek(device: MediaDevice, positionMs: Long) {}
    override suspend fun status(device: MediaDevice) = RendererStatus(TransportState.PLAYING, 0, 0)
    override suspend fun volume(device: MediaDevice) = 50
    override suspend fun setVolume(device: MediaDevice, volume: Int) {}
}

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
        CastReceiverSession(receiver, settings, PlaybackSession(NoRenderer(), backgroundScope), backgroundScope).start()
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
        val renderer = NoRenderer()
        val playback = PlaybackSession(renderer, backgroundScope)
        val session = CastReceiverSession(receiver, ReceiverSettings(enabled = true), playback, backgroundScope)
        playback.setTarget(PlaybackTarget.Renderer(MediaDevice("uuid:tv", "TV", "", "", "h", null, false, true)))
        session.start()
        val opened = backgroundScope.launch { session.openPlayer.first() }
        val commands = mutableListOf<LocalPlayerCommand>()
        backgroundScope.launch { playback.localCommands.take(4).toList(commands) }
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
        assertTrue(opened.isCompleted)
        val reopened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()
        receiver.commands.emit(CastCommand.Play) // player closed meanwhile → Play brings it back
        runCurrent()
        assertTrue(reopened.isCompleted)
        assertTrue("stop" in renderer.calls) // the TV we were casting to is released
        assertEquals(listOf(LocalPlayerCommand.Play, LocalPlayerCommand.Seek(42_000), LocalPlayerCommand.Stop, LocalPlayerCommand.Play), commands)
    }

    @Test
    fun aNewLoadDropsCommandsTheOldPlayerNeverRead() = runTest {
        val playback = PlaybackSession(NoRenderer(), backgroundScope)
        playback.play(clip, listOf(clip))
        playback.sendLocal(LocalPlayerCommand.Seek(1))
        playback.play(photo, listOf(photo), autoPlay = false)
        playback.sendLocal(LocalPlayerCommand.Play)
        assertEquals(LocalPlayerCommand.Play, playback.localCommands.first())
    }

    @Test
    fun publishesWhatThePlayerReports() = runTest {
        val receiver = FakeReceiver()
        val playback = PlaybackSession(NoRenderer(), backgroundScope)
        CastReceiverSession(receiver, ReceiverSettings(enabled = true), playback, backgroundScope).start()
        runCurrent()
        assertNull(receiver.published.last().entry)

        receiver.commands.emit(CastCommand.Load(clip, autoPlay = true))
        runCurrent()
        assertNull(receiver.published.last().status) // loaded, player hasn't reported yet

        playback.reportLocalStatus(RendererStatus(TransportState.PLAYING, 5_000, 60_000))
        playback.setLocalVolume(70)
        runCurrent()
        assertEquals(ReceiverPlayback(clip, RendererStatus(TransportState.PLAYING, 5_000, 60_000), 70, false), receiver.published.last())

        // Photos have no player behind them: shown means playing.
        receiver.commands.emit(CastCommand.Load(photo, autoPlay = true))
        runCurrent()
        assertEquals(RendererStatus(TransportState.PLAYING, 0, 0), receiver.published.last().status)
    }
}

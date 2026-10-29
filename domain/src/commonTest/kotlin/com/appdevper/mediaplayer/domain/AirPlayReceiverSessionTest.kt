package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.testing.FakeSettings
import com.appdevper.mediaplayer.testing.FakeAirPlay
import com.appdevper.mediaplayer.testing.FakeEngine
import com.appdevper.mediaplayer.testing.FakeRenderer
import com.appdevper.mediaplayer.testing.playbackSession
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayNowPlaying
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.NowPlaying
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun AirPlaySettings(enabled: Boolean) = FakeSettings(ServerSettings("Living Room", false, true, true, true, emptyList(), airPlayEnabled = enabled))

@OptIn(ExperimentalCoroutinesApi::class)
class AirPlayReceiverSessionTest {

    @Test
    fun followsSettings() = runTest {
        val airPlay = FakeAirPlay()
        val settings = AirPlaySettings(enabled = false)
        AirPlayReceiverSession(airPlay, settings, playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope), backgroundScope).start()
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
        val airPlay = FakeAirPlay(AirPlayStatus.Unsupported)
        AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope), backgroundScope).start()
        runCurrent()
        assertTrue(airPlay.calls.none { it.startsWith("start") })
    }

    @Test
    fun mirroringOpensTheScreenAndPausesOurPlayer() = runTest {
        val airPlay = FakeAirPlay()
        val engine = FakeEngine()
        val playback = playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope, engine)
        playback.play(MediaEntry("v", "0", "Movie", MediaKind.VIDEO, streamUrl = "http://h/v.mp4"), emptyList())
        val session = AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope)
        session.start()
        val opened = backgroundScope.launch { session.openMirror.first() }
        runCurrent()
        assertFalse(opened.isCompleted)

        airPlay.activity.value = AirPlayActivity(client = "Worawit's iPhone", mirroring = true)
        runCurrent()
        assertTrue(opened.isCompleted)
        assertEquals(TransportState.PAUSED, playback.state.value.playback.status?.state)

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
        val playback = playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope, engine)
        val session = AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope)
        session.start()
        val opened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()

        val video = MediaEntry("v", "-1", "YouTube", MediaKind.VIDEO, streamUrl = "http://localhost:7000/master.m3u8")
        airPlay.send(CastCommand.Load(video, autoPlay = true))
        airPlay.send(CastCommand.Seek(30_000))
        airPlay.send(CastCommand.Pause)
        runCurrent()
        assertEquals(listOf(video), playback.state.value.queue)
        assertTrue(opened.isCompleted)
        assertEquals(listOf("load:v@0", "seek:30000", "pause"), engine.calls)
        assertEquals(ReceiverPlayback(video, RendererStatus(TransportState.PAUSED, 30_000, 60_000)), airPlay.published.last())

        // Stopped by the sender: shown again later (after AirPlay audio), it must not start by itself.
        airPlay.send(CastCommand.Stop)
        runCurrent()
        assertEquals(false, playback.state.value.autoPlay)

        // A sender resuming at a position: the player starts there (no separate seek to race with the old player).
        airPlay.send(CastCommand.Load(video.copy(id = "v2"), autoPlay = true, startPositionMs = 157_000))
        runCurrent()
        assertEquals(157_000, playback.state.value.startPositionMs)
        assertEquals(true, playback.state.value.autoPlay)
        assertEquals("load:v2@157000", engine.calls.last())
    }

    @Test
    fun theSendersQueueDecidesNextAndPreviousForItsVideo() = runTest {
        val airPlay = FakeAirPlay()
        val engine = FakeEngine()
        val playback = playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope, engine, sender = airPlay)
        AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope).start()
        runCurrent()
        val video = MediaEntry("yt", "-1", "YouTube", MediaKind.VIDEO, streamUrl = "http://localhost:7000/master.m3u8")
        airPlay.send(CastCommand.Load(video, autoPlay = true))
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
        val playback = playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope)
        playback.play(MediaEntry("a", "0", "Song", MediaKind.AUDIO, streamUrl = "http://h/a.mp3"), emptyList())
        val session = AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope)
        session.start()
        runCurrent()
        val opened = backgroundScope.launch { session.openPlayer.first() }
        runCurrent()
        airPlay.activity.value = AirPlayActivity(client = "Mac", audioOnly = true)
        runCurrent()
        assertEquals(TransportState.PAUSED, playback.state.value.playback.status?.state)
        assertTrue(opened.isCompleted, "music from a sender opens the player screen")
    }

    @Test
    fun nowPlayingIsTheSendersMusicAndItsControlsGoThere() = runTest {
        val airPlay = FakeAirPlay()
        val engine = FakeEngine()
        val playback = playbackSession(FakeRenderer(state = TransportState.STOPPED), backgroundScope, engine, sender = airPlay)
        val song = MediaEntry("a", "0", "Song", MediaKind.AUDIO, streamUrl = "http://h/a.mp3")
        playback.play(song, emptyList())
        AirPlayReceiverSession(airPlay, AirPlaySettings(enabled = true), playback, backgroundScope).start()
        runCurrent()
        assertEquals(NowPlaying.Item(song, PlaybackTarget.Local, playing = true, senderSkips = false), playback.nowPlaying.value)

        airPlay.activity.value = AirPlayActivity(client = "Mac", audioOnly = true, canControlSender = true)
        runCurrent()
        val music = assertIs<NowPlaying.SenderAudio>(playback.nowPlaying.value)
        assertEquals(true, music.playing)
        assertEquals("Mac", music.sender)

        // Play/pause anywhere (the mini player, the lock screen) goes to the sender, not our paused song.
        engine.calls.clear()
        playback.togglePause()
        runCurrent()
        assertEquals(listOf(SenderCommand.PlayPause), airPlay.sentToSender)
        assertEquals(false, playback.nowPlaying.value.playing)
        assertTrue(engine.calls.none { it == "play" })
        // Skipped on the sender itself: the new song plays.
        airPlay.activity.value = AirPlayActivity(client = "Mac", audioOnly = true, canControlSender = true, nowPlaying = AirPlayNowPlaying("Next song"))
        runCurrent()
        assertEquals(true, playback.nowPlaying.value.playing)
        assertTrue(playback.next())
        runCurrent()
        assertEquals(SenderCommand.Next, airPlay.sentToSender.last())

        // A sender without remote control: no play/pause to offer, and our controls don't reach it.
        airPlay.activity.value = AirPlayActivity(client = "Mac", audioOnly = true)
        runCurrent()
        assertEquals(null, playback.nowPlaying.value.playing)
        assertFalse(playback.nowPlaying.value.senderSkips)

        // Its music stopped: our song again.
        airPlay.activity.value = AirPlayActivity(client = "Mac")
        runCurrent()
        assertIs<NowPlaying.Item>(playback.nowPlaying.value)
    }
}

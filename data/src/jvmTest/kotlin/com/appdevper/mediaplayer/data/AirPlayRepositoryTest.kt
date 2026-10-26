package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.airplay.AirPlayAccess
import com.appdevper.mediaplayer.airplay.AirPlayAudioCodec
import com.appdevper.mediaplayer.airplay.AirPlayConfig
import com.appdevper.mediaplayer.airplay.AirPlayEvent
import com.appdevper.mediaplayer.airplay.AirPlayException
import com.appdevper.mediaplayer.airplay.AirPlayMediaSink
import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.airplay.AirPlayReceiver
import com.appdevper.mediaplayer.airplay.AirPlayVideoControl
import com.appdevper.mediaplayer.airplay.AirPlayVideoInfo
import com.appdevper.mediaplayer.data.platform.AppStorage
import com.appdevper.mediaplayer.data.platform.MulticastLock
import com.appdevper.mediaplayer.data.repository.AirPlayRepositoryImpl
import com.appdevper.mediaplayer.data.repository.SettingsRepositoryImpl
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeReceiver(var failWith: String? = null) : AirPlayReceiver {
    val configs = mutableListOf<AirPlayConfig>()
    var stops = 0
    override val events = MutableSharedFlow<AirPlayEvent>(extraBufferCapacity = 16)
    override var sink: AirPlayMediaSink? = null
    override var access: AirPlayAccess = object : AirPlayAccess {}
    override var video: AirPlayVideoControl? = null
    override val port: Int? get() = if (configs.isNotEmpty()) 7000 else null
    override fun start(config: AirPlayConfig) {
        failWith?.let { throw AirPlayException(it) }
        configs += config
    }
    override fun stop() { stops++ }
}

private class CountingLock : MulticastLock {
    var held = 0
    override fun acquire() { held = 1 }
    override fun release() { held = 0 }
}

class AirPlayRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = SettingsRepositoryImpl(MapSettings())
    private val storage = object : AppStorage { override val dir = "/data/app" }
    private val player = AirPlayPlayer()

    @AfterTest
    fun tearDown() {
        player.release()
        scope.cancel()
    }

    @Test
    fun startsWithAStableIdentityAndHoldsMulticast() = runBlocking<Unit> {
        val receiver = FakeReceiver()
        val lock = CountingLock()
        val repo = AirPlayRepositoryImpl(receiver, player, settings, storage, lock, scope)
        assertSame(player, receiver.sink) // the player gets the streams

        repo.start("Living Room", requirePin = true)
        assertEquals(AirPlayStatus.Running("Living Room"), repo.status.value)
        assertEquals(1, lock.held)
        val first = receiver.configs.single()
        assertEquals("/data/app/airplay_key.pem", first.keyFile)
        assertTrue(first.requirePin)
        assertEquals(2, first.deviceId[0].toInt() and 0x03) // locally administered, unicast

        repo.start("Living Room", requirePin = false)
        assertTrue(first.deviceId.contentEquals(receiver.configs.last().deviceId)) // same id every time

        repo.stop()
        assertEquals(AirPlayStatus.Stopped, repo.status.value)
        assertEquals(0, lock.held)
    }

    @Test
    fun failureIsReported() = runBlocking<Unit> {
        val lock = CountingLock()
        val repo = AirPlayRepositoryImpl(FakeReceiver(failWith = "mDNS port busy"), player, settings, storage, lock, scope)
        repo.start("X", requirePin = false)
        assertEquals(AirPlayStatus.Failed("mDNS port busy"), repo.status.value)
        assertEquals(0, lock.held)
    }

    @Test
    fun unsupportedWithoutAReceiver() = runBlocking<Unit> {
        val repo = AirPlayRepositoryImpl(null, player, settings, storage, CountingLock(), scope)
        assertIs<AirPlayStatus.Unsupported>(repo.status.value)
        repo.start("X", requirePin = false)
        assertIs<AirPlayStatus.Unsupported>(repo.status.value)
    }

    @Test
    fun airPlayVideoDrivesOurPlayerAndReportsProgress() = runBlocking<Unit> {
        val receiver = FakeReceiver()
        val repo = AirPlayRepositoryImpl(receiver, player, settings, storage, CountingLock(), scope)
        val video = assertNotNull(receiver.video)
        val commands = mutableListOf<CastCommand>()
        val collecting = scope.launch { repo.commands.collect { commands += it } }
        receiver.events.emit(AirPlayEvent.ClientConnecting("Worawit's iPhone", "iPhone17,1", "aa"))

        val url = "http://localhost:7000/master.m3u8"
        video.play(url, startSeconds = 12.5f)
        val load = assertIs<CastCommand.Load>(commands[0])
        assertEquals(url, load.entry.streamUrl)
        assertEquals("AirPlay from Worawit's iPhone", load.entry.title)
        assertEquals("application/x-mpegURL", load.entry.mimeType)
        assertEquals(CastCommand.Seek(12_500), commands[1])
        assertNull(video.info()) // player hasn't reported yet

        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.PLAYING, 13_000, 300_000)))
        assertEquals(AirPlayVideoInfo(300.0, 13.0, playing = true), video.info())

        video.setRate(0f)
        video.seek(60f)
        video.setRate(1f)
        assertEquals(listOf(CastCommand.Pause, CastCommand.Seek(60_000), CastCommand.Play), commands.drop(2))

        // Played to the end: the sender is told it's over.
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 300_000, 300_000)))
        assertEquals(-1.0, video.info()?.durationSeconds)

        // The user played something else here: also over.
        repo.publish(ReceiverPlayback(load.entry.copy(id = "nas-movie", streamUrl = "http://nas/movie.mp4"), RendererStatus(TransportState.PLAYING, 0, 0)))
        assertEquals(-1.0, video.info()?.durationSeconds)

        // YouTube's "next video": same local URL again, must still be a new item.
        video.play(url, startSeconds = 0f)
        val next = assertIs<CastCommand.Load>(commands.last())
        assertEquals(url, next.entry.streamUrl)
        assertTrue(next.entry.id != load.entry.id, "a new item, so the player loads the new video")
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.PLAYING, 50_000, 300_000)))
        assertNull(video.info()) // the old video's progress isn't reported as the new one's
        repo.publish(ReceiverPlayback(next.entry, RendererStatus(TransportState.PLAYING, 1_000, 200_000)))
        assertEquals(AirPlayVideoInfo(200.0, 1.0, playing = true), video.info())

        video.stop()
        assertEquals(CastCommand.Stop, commands.last())
        assertNull(video.info())
        collecting.cancel()
    }

    @Test
    fun eventsBecomeActivityAndPairingIsRemembered() = runBlocking<Unit> {
        val receiver = FakeReceiver()
        val repo = AirPlayRepositoryImpl(receiver, player, settings, storage, CountingLock(), scope)
        repo.start("TV", requirePin = true)
        assertTrue(!receiver.access.isPaired("pk1"))

        receiver.events.emit(AirPlayEvent.Connections(1))
        receiver.events.emit(AirPlayEvent.ClientConnecting("Worawit's iPhone", "iPhone17,1", "aa:bb"))
        receiver.events.emit(AirPlayEvent.ShowPin("4821"))
        assertEquals(AirPlayActivity(client = "Worawit's iPhone", pin = "4821"), repo.activity.value)

        receiver.events.emit(AirPlayEvent.ClientPaired("aa:bb", "pk1", "Worawit's iPhone"))
        assertEquals(null, repo.activity.value.pin)
        assertTrue(receiver.access.isPaired("pk1")) // no PIN next time

        receiver.events.emit(AirPlayEvent.MirroringStarted(false, 1920, 1080))
        receiver.events.emit(AirPlayEvent.AudioStarted(AirPlayAudioCodec.AAC_ELD, 480, withScreen = true))
        assertEquals(AirPlayActivity(client = "Worawit's iPhone", mirroring = true), repo.activity.value)

        receiver.events.emit(AirPlayEvent.MirroringStopped)
        receiver.events.emit(AirPlayEvent.AudioStarted(AirPlayAudioCodec.PCM, 352, withScreen = false))
        assertEquals(AirPlayActivity(client = "Worawit's iPhone", audioOnly = true), repo.activity.value)

        receiver.events.emit(AirPlayEvent.Connections(0))
        assertEquals(AirPlayActivity(), repo.activity.value)
    }
}

package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.airplay.AirPlayAccess
import com.appdevper.mediaplayer.airplay.AirPlayAudioCodec
import com.appdevper.mediaplayer.airplay.AirPlayConfig
import com.appdevper.mediaplayer.airplay.AirPlayEvent
import com.appdevper.mediaplayer.airplay.AirPlayException
import com.appdevper.mediaplayer.airplay.AirPlayMediaSink
import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.airplay.AirPlayReceiver
import com.appdevper.mediaplayer.airplay.AirPlayTrackInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun anEndedVideoTheSenderAbandonsIsClosedHere() = runBlocking<Unit> {
        val receiver = FakeReceiver()
        val repo = AirPlayRepositoryImpl(receiver, player, settings, storage, CountingLock(), scope, abandonedEndMs = 100)
        val video = assertNotNull(receiver.video)
        val commands = mutableListOf<CastCommand>()
        val collecting = scope.launch { repo.commands.collect { commands += it } }

        video.play("http://localhost:7000/master.m3u8", startSeconds = 0f)
        val load = assertIs<CastCommand.Load>(commands.first())
        // Stopped 2 s short of the container duration: still the end (it used to need < 1 s).
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 298_000, 300_000), volume = 80))
        assertEquals(AirPlayVideoInfo(300.0, 300.0, playing = false), video.info())

        // The phone went away without /stop: the session ends here, so a later /rate 1 can't restart it.
        delay(400)
        assertEquals(CastCommand.Stop, commands.last())
        assertNull(video.info())
        val before = commands.size
        video.setRate(1f)
        assertEquals(before, commands.size)
        collecting.cancel()
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
        assertEquals(12_500, load.startPositionMs) // part of the load, not a separate seek
        assertNull(video.info()) // player hasn't reported yet

        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.PLAYING, 13_000, 300_000)))
        assertEquals(AirPlayVideoInfo(300.0, 13.0, playing = true), video.info())

        video.setRate(0f)
        video.seek(60f)
        video.setRate(1f)
        assertEquals(listOf(CastCommand.Pause, CastCommand.Seek(60_000), CastCommand.Play), commands.drop(1))

        // Played to the end: reported like an Apple TV does (at the end, paused), so the sender decides
        // what's next. Duration -1 made UxPlay drop the connection and YouTube showed "Something went wrong".
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 299_400, 300_000)))
        assertEquals(AirPlayVideoInfo(300.0, 300.0, playing = false), video.info())

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

        // YouTube's "next": /stop, then /rate 1 before the new /play. That rate must not resume the
        // stopped video (the old song played until the new one loaded).
        val before = commands.size
        video.setRate(1f)
        video.seek(10f)
        assertEquals(before, commands.size)

        // The sender's volume buttons (sent as AirPlay audio volume) drive the video player,
        // and our own volume comes back when the video stops.
        repo.publish(ReceiverPlayback(volume = 80))
        receiver.events.emit(AirPlayEvent.Volume(-15f))
        assertEquals(before, commands.size) // no video now: only the AirPlay audio player follows
        video.play(url, startSeconds = 0f)
        assertEquals(CastCommand.SetVolume(25), commands.last()) // the last volume applies to the new video
        receiver.events.emit(AirPlayEvent.Volume(0f))
        assertEquals(CastCommand.SetVolume(100), commands.last())
        video.stop()
        assertEquals(CastCommand.SetVolume(80), commands.last())
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

        // Music app: song, cover art and progress for the player screen.
        val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)
        receiver.events.emit(AirPlayEvent.Track(AirPlayTrackInfo("Khon Kaen Blues", "Bodyslam", "Kraam")))
        receiver.events.emit(AirPlayEvent.Artwork(cover))
        receiver.events.emit(AirPlayEvent.Progress(positionMs = 30_000, durationMs = 245_000))
        val song = assertNotNull(repo.activity.value.nowPlaying)
        assertEquals("Khon Kaen Blues", song.title)
        assertEquals("Bodyslam", song.artist)
        assertEquals("Kraam", song.album)
        assertTrue(song.artwork.contentEquals(cover))
        assertEquals(245_000, song.durationMs)
        assertTrue(song.positionNow() in 30_000..31_000)
        receiver.events.emit(AirPlayEvent.Artwork(null)) // sender cleared it
        assertNull(repo.activity.value.nowPlaying?.artwork)

        // The sender ends its audio stream but stays connected: no longer "playing audio here".
        receiver.events.emit(AirPlayEvent.AudioStopped)
        assertFalse(repo.activity.value.audioOnly)
        assertNull(repo.activity.value.nowPlaying)

        // YouTube Music going from song (AirPlay audio) to video: the video takes over the player.
        receiver.events.emit(AirPlayEvent.AudioStarted(AirPlayAudioCodec.AAC_ELD, 480, withScreen = false))
        receiver.events.emit(AirPlayEvent.Track(AirPlayTrackInfo("Khon Kaen Blues", "Bodyslam", "Kraam")))
        assertTrue(repo.activity.value.audioOnly)
        assertNotNull(receiver.video).play("http://localhost:7000/master.m3u8", startSeconds = 25f)
        assertFalse(repo.activity.value.audioOnly)
        assertNull(repo.activity.value.nowPlaying)

        receiver.events.emit(AirPlayEvent.Connections(0))
        assertEquals(AirPlayActivity(), repo.activity.value)
    }
}

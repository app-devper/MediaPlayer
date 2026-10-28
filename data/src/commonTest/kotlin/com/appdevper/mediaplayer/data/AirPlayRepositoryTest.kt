package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.airplay.AirPlayAccess
import com.appdevper.mediaplayer.airplay.AirPlayAudioCodec
import com.appdevper.mediaplayer.airplay.AirPlayConfig
import com.appdevper.mediaplayer.airplay.AirPlayEvent
import com.appdevper.mediaplayer.airplay.AirPlayException
import com.appdevper.mediaplayer.airplay.AirPlayMediaSink
import com.appdevper.mediaplayer.airplay.AirPlayReceiver
import com.appdevper.mediaplayer.airplay.AirPlayRemoteCommand
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
import com.appdevper.mediaplayer.domain.model.ReceiverLink
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.TransportState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    val sent = mutableListOf<AirPlayRemoteCommand>()
    override suspend fun sendToSender(command: AirPlayRemoteCommand): Boolean { sent += command; return true }
}

private class CountingLock : MulticastLock {
    var held = 0
    override fun acquire() { held = 1 }
    override fun release() { held = 0 }
}

private object NoSink : AirPlayMediaSink

/** The playback session as AirPlay sees it: owned from a load until [owns] is cleared (the user played something else). */
private class FakeLink : ReceiverLink {
    val commands = mutableListOf<CastCommand>()
    override var owns = false
    override fun send(command: CastCommand): Boolean {
        if (command is CastCommand.Load) owns = true
        else if (!owns && command !is CastCommand.SetVolume) return false
        commands += command
        return true
    }
}

class AirPlayRepositoryTest {
    private val settings = SettingsRepositoryImpl(MapSettings())
    private val storage = object : AppStorage { override val dir = "/data/app" }
    private val gains = mutableListOf<Float>()

    private fun TestScope.repo(receiver: AirPlayReceiver?, lock: MulticastLock = CountingLock(), abandonedEndMs: Long = 5 * 60_000) =
        AirPlayRepositoryImpl(receiver, NoSink, { gains += it }, settings, storage, lock, backgroundScope as CoroutineScope, abandonedEndMs)
            .also { runCurrent() } // listening to the receiver's events

    @Test
    fun startsWithAStableIdentityAndHoldsMulticast() = runTest {
        val receiver = FakeReceiver()
        val lock = CountingLock()
        val repo = repo(receiver, lock)
        assertSame(NoSink, receiver.sink) // the player gets the streams

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
    fun failureIsReported() = runTest {
        val lock = CountingLock()
        val repo = repo(FakeReceiver(failWith = "mDNS port busy"), lock)
        repo.start("X", requirePin = false)
        assertEquals(AirPlayStatus.Failed("mDNS port busy"), repo.status.value)
        assertEquals(0, lock.held)
    }

    @Test
    fun unsupportedWithoutAReceiver() = runTest {
        val repo = repo(null)
        assertIs<AirPlayStatus.Unsupported>(repo.status.value)
        repo.start("X", requirePin = false)
        assertIs<AirPlayStatus.Unsupported>(repo.status.value)
    }

    @Test
    fun anEndedVideoTheSenderAbandonsIsClosedHere() = runTest {
        val receiver = FakeReceiver()
        val repo = repo(receiver, abandonedEndMs = 100)
        val link = FakeLink().also(repo::connect)
        val video = assertNotNull(receiver.video)

        video.play("http://localhost:7000/master.m3u8", startSeconds = 0f)
        val load = assertIs<CastCommand.Load>(link.commands.first())
        // Leaving the player mid-video reports STOPPED too, but that isn't the end.
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 120_000, 300_000)))
        assertEquals(AirPlayVideoInfo(300.0, 120.0, playing = false), video.info())
        advanceTimeBy(400)
        assertIs<CastCommand.Load>(link.commands.last()) // not closed

        // Ended 2 s short of the container duration: still the end.
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 298_000, 300_000, ended = true)))
        assertEquals(AirPlayVideoInfo(300.0, 300.0, playing = false), video.info())
        // The phone seeks back after the end: that video isn't finished any more, the timer is cancelled.
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 200_000, 300_000)))
        assertEquals(AirPlayVideoInfo(300.0, 200.0, playing = false), video.info())
        advanceTimeBy(400)
        assertIs<CastCommand.Load>(link.commands.last())

        // Ended again, and nothing from the sender: the session ends here, so a later /rate 1 can't restart it.
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 298_000, 300_000, ended = true)))
        advanceTimeBy(400)
        assertEquals(CastCommand.Stop, link.commands.last())
        assertNull(video.info())
        val before = link.commands.size
        video.setRate(1f)
        assertEquals(before, link.commands.size)
    }

    @Test
    fun anEndedVideoReplacedHereIsNeitherClosedNorDrivenBySender() = runTest {
        val receiver = FakeReceiver()
        val repo = repo(receiver, abandonedEndMs = 100)
        val link = FakeLink().also(repo::connect)
        val video = assertNotNull(receiver.video)
        video.play("http://localhost:7000/master.m3u8", startSeconds = 0f)
        val load = assertIs<CastCommand.Load>(link.commands.first())
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 298_000, 300_000, ended = true)))

        // Something else starts here before the timeout: not stopped by it, nor driven by a late /rate or /scrub.
        link.owns = false
        repo.publish(ReceiverPlayback(load.entry.copy(id = "nas-movie"), RendererStatus(TransportState.PLAYING, 1_000, 5_000_000)))
        advanceTimeBy(400)
        video.setRate(0f)
        video.seek(10f)
        assertEquals(listOf<CastCommand>(load), link.commands)
        // And the sender is told its video is over.
        assertEquals(-1.0, video.info()?.durationSeconds)
    }

    @Test
    fun airPlayVideoDrivesOurPlayerAndReportsProgress() = runTest {
        val receiver = FakeReceiver()
        val repo = repo(receiver)
        val link = FakeLink().also(repo::connect)
        val video = assertNotNull(receiver.video)
        receiver.events.emit(AirPlayEvent.ClientConnecting("Worawit's iPhone", "iPhone17,1", "aa"))
        runCurrent()

        val url = "http://localhost:7000/master.m3u8"
        video.play(url, startSeconds = 12.5f)
        val load = assertIs<CastCommand.Load>(link.commands[0])
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
        assertEquals(listOf(CastCommand.Pause, CastCommand.Seek(60_000), CastCommand.Play), link.commands.drop(1))

        // Played to the end: reported like an Apple TV does (at the end, paused), so the sender decides
        // what's next. Duration -1 made UxPlay drop the connection and YouTube showed "Something went wrong".
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.STOPPED, 299_400, 300_000, ended = true)))
        assertEquals(AirPlayVideoInfo(300.0, 300.0, playing = false), video.info())

        // YouTube's "next video": same local URL again, must still be a new item.
        video.play(url, startSeconds = 0f)
        val next = assertIs<CastCommand.Load>(link.commands.last())
        assertEquals(url, next.entry.streamUrl)
        assertTrue(next.entry.id != load.entry.id, "a new item, so the player loads the new video")
        repo.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.PLAYING, 50_000, 300_000)))
        assertNull(video.info()) // the old video's progress isn't reported as the new one's
        repo.publish(ReceiverPlayback(next.entry, RendererStatus(TransportState.PLAYING, 1_000, 200_000)))
        assertEquals(AirPlayVideoInfo(200.0, 1.0, playing = true), video.info())

        video.stop()
        assertEquals(CastCommand.Stop, link.commands.last())
        assertNull(video.info())

        // YouTube's "next": /stop, then /rate 1 before the new /play. That rate must not resume the
        // stopped video (the old song played until the new one loaded).
        val before = link.commands.size
        video.setRate(1f)
        video.seek(10f)
        assertEquals(before, link.commands.size)

        // The sender's volume buttons (sent as AirPlay audio volume) drive the video player,
        // and our own volume comes back when the video stops.
        repo.publish(ReceiverPlayback(volume = 80))
        receiver.events.emit(AirPlayEvent.Volume(-15f))
        runCurrent()
        assertEquals(before, link.commands.size) // no video now: only the AirPlay audio player follows
        assertEquals(0.25f, gains.last(), 0.01f)
        video.play(url, startSeconds = 0f)
        assertEquals(CastCommand.SetVolume(25), link.commands.last()) // the last volume applies to the new video
        receiver.events.emit(AirPlayEvent.Volume(0f))
        runCurrent()
        assertEquals(CastCommand.SetVolume(100), link.commands.last())
        video.stop()
        assertEquals(CastCommand.SetVolume(80), link.commands.last())
    }

    @Test
    fun theSenderCanBeRemoteControlled() = runTest {
        val receiver = FakeReceiver()
        val repo = repo(receiver)
        val link = FakeLink().also(repo::connect)
        assertFalse(repo.activity.value.canControlSender)
        receiver.events.emit(AirPlayEvent.Connections(1))
        receiver.events.emit(AirPlayEvent.RemoteControlAvailable)
        runCurrent()
        assertTrue(repo.activity.value.canControlSender)

        // Our player's entry for the sender's video is known while it's current.
        val video = assertNotNull(receiver.video)
        video.play("http://localhost:7000/master.m3u8", startSeconds = 0f)
        assertEquals(assertIs<CastCommand.Load>(link.commands.first()).entry.id, repo.activity.value.videoEntryId)

        assertTrue(repo.sendToSender(SenderCommand.Next))
        assertTrue(repo.sendToSender(SenderCommand.Previous))
        assertTrue(repo.sendToSender(SenderCommand.PlayPause))
        assertEquals(listOf(AirPlayRemoteCommand.Next, AirPlayRemoteCommand.Previous, AirPlayRemoteCommand.PlayPause), receiver.sent)

        video.stop()
        assertNull(repo.activity.value.videoEntryId)
        // The sender's control port went away while another connection stays open: controls off.
        receiver.events.emit(AirPlayEvent.RemoteControlLost)
        runCurrent()
        assertFalse(repo.activity.value.canControlSender)
        receiver.events.emit(AirPlayEvent.RemoteControlAvailable)
        receiver.events.emit(AirPlayEvent.Connections(0))
        runCurrent()
        assertFalse(repo.activity.value.canControlSender)
    }

    @Test
    fun eventsBecomeActivityAndPairingIsRemembered() = runTest {
        val receiver = FakeReceiver()
        val repo = repo(receiver)
        repo.connect(FakeLink())
        repo.start("TV", requirePin = true)
        assertTrue(!receiver.access.isPaired("pk1"))

        suspend fun emit(vararg events: AirPlayEvent) {
            events.forEach { receiver.events.emit(it) }
            runCurrent()
        }
        emit(
            AirPlayEvent.Connections(1),
            AirPlayEvent.ClientConnecting("Worawit's iPhone", "iPhone17,1", "aa:bb"),
            AirPlayEvent.ShowPin("4821"),
        )
        assertEquals(AirPlayActivity(client = "Worawit's iPhone", pin = "4821"), repo.activity.value)

        emit(AirPlayEvent.ClientPaired("aa:bb", "pk1", "Worawit's iPhone"))
        assertEquals(null, repo.activity.value.pin)
        assertTrue(receiver.access.isPaired("pk1")) // no PIN next time

        emit(AirPlayEvent.MirroringStarted(false, 1920, 1080), AirPlayEvent.AudioStarted(AirPlayAudioCodec.AAC_ELD, 480, withScreen = true))
        assertEquals(AirPlayActivity(client = "Worawit's iPhone", mirroring = true), repo.activity.value)

        emit(AirPlayEvent.MirroringStopped, AirPlayEvent.AudioStarted(AirPlayAudioCodec.PCM, 352, withScreen = false))
        assertEquals(AirPlayActivity(client = "Worawit's iPhone", audioOnly = true), repo.activity.value)

        // Music app: song, cover art and progress for the player screen.
        val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)
        emit(
            AirPlayEvent.Track(AirPlayTrackInfo("Khon Kaen Blues", "Bodyslam", "Kraam")),
            AirPlayEvent.Artwork(cover),
            AirPlayEvent.Progress(positionMs = 30_000, durationMs = 245_000),
        )
        val song = assertNotNull(repo.activity.value.nowPlaying)
        assertEquals("Khon Kaen Blues", song.title)
        assertEquals("Bodyslam", song.artist)
        assertEquals("Kraam", song.album)
        assertTrue(song.artwork.contentEquals(cover))
        assertEquals(245_000, song.durationMs)
        assertTrue(song.positionNow() in 30_000..31_000)
        emit(AirPlayEvent.Artwork(null)) // sender cleared it
        assertNull(repo.activity.value.nowPlaying?.artwork)

        // The sender ends its audio stream but stays connected: no longer "playing audio here".
        emit(AirPlayEvent.AudioStopped)
        assertFalse(repo.activity.value.audioOnly)
        assertNull(repo.activity.value.nowPlaying)

        // YouTube Music going from song (AirPlay audio) to video: the video takes over the player.
        emit(AirPlayEvent.AudioStarted(AirPlayAudioCodec.AAC_ELD, 480, withScreen = false), AirPlayEvent.Track(AirPlayTrackInfo("Khon Kaen Blues", "Bodyslam", "Kraam")))
        assertTrue(repo.activity.value.audioOnly)
        assertNotNull(receiver.video).play("http://localhost:7000/master.m3u8", startSeconds = 25f)
        assertFalse(repo.activity.value.audioOnly)
        assertNull(repo.activity.value.nowPlaying)

        emit(AirPlayEvent.Connections(0))
        assertEquals(AirPlayActivity(), repo.activity.value)
    }
}

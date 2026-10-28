package com.appdevper.mediaplayer

import com.appdevper.mediaplayer.testing.FakeServer
import com.appdevper.mediaplayer.testing.FakeEngine
import com.appdevper.mediaplayer.testing.FakeRenderer
import com.appdevper.mediaplayer.testing.FakeAirPlay
import com.appdevper.mediaplayer.testing.FakeReceiver
import com.appdevper.mediaplayer.testing.FakeSubtitles
import com.appdevper.mediaplayer.domain.model.SubtitleState
import com.appdevper.mediaplayer.domain.model.SubtitleChoice
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.data.di.dataModule
import com.appdevper.mediaplayer.data.di.platformDataModule
import com.appdevper.mediaplayer.di.domainModule
import com.appdevper.mediaplayer.di.platformAppModule
import com.appdevper.mediaplayer.di.presentationModule
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.session.CastReceiverSession
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.first
import com.appdevper.mediaplayer.feature.mirror.MirrorViewModel
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.domain.session.MediaServerSession
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.appdevper.mediaplayer.domain.usecase.BrowseFolderUseCase
import com.appdevper.mediaplayer.domain.usecase.FilterEntriesUseCase
import com.appdevper.mediaplayer.feature.browse.BrowseViewModel
import com.appdevper.mediaplayer.feature.player.PlayerViewModel
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.RepeatMode
import com.appdevper.mediaplayer.feature.player.Modes
import com.appdevper.mediaplayer.feature.player.PlayerUi
import com.appdevper.mediaplayer.feature.server.ServerViewModel
import com.appdevper.mediaplayer.feature.shell.ShellViewModel
import com.appdevper.mediaplayer.domain.model.MediaDevice
import kotlinx.coroutines.test.advanceUntilIdle
import com.appdevper.mediaplayer.feature.shell.AddServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun koinGraphResolvesEveryViewModel() {
        val koin = startKoin { modules(dataModule, platformDataModule, domainModule, presentationModule, platformAppModule) }.koin
        assertNotNull(koin.get<ShellViewModel>())
        assertNotNull(koin.get<PlayerViewModel>())
        assertNotNull(koin.get<ServerViewModel>())
        assertNotNull(koin.get<MirrorViewModel>())
        assertNotNull(koin.get<BrowseViewModel> { parametersOf("uuid:x", "0", "Root") })
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val engine = FakeEngine()
    private val renderer = FakeRenderer()
    private val airPlay = FakeAirPlay(AirPlayStatus.Running("Mac"))
    private val session = PlaybackSession(renderer, LocalPlayer(engine, appScope, subtitleFiles = FakeSubtitles()), appScope, sender = airPlay)

    private fun serverSession(server: FakeServer, settings: FakeSettings) = MediaServerSession(server, settings, appScope).also { it.start() }

    private fun receiverSession(settings: FakeSettings = FakeSettings(), receiver: FakeReceiver = FakeReceiver()) =
        CastReceiverSession(receiver, settings, session, appScope)

    private fun airPlaySession(settings: FakeSettings = FakeSettings(), airPlay: FakeAirPlay = this.airPlay) =
        AirPlayReceiverSession(airPlay, settings, session, appScope)

    @Test
    fun browseLoadsFiltersAndPlays() = runTest {
        val library = FakeLibrary()
        val vm = BrowseViewModel("uuid:nas", "0", "Root", FakeDevices(), BrowseFolderUseCase(library), FilterEntriesUseCase(), session)
        val s = vm.uiState.value
        assertEquals("Living Room NAS", s.deviceName)
        assertEquals(listOf("Movies", "Music"), s.visible.take(2).map { it.title }) // folders first

        vm.onQueryChange("bodyslam")
        assertEquals(listOf("a1"), vm.uiState.value.visible.map { it.id })

        assertTrue(vm.play(vm.uiState.value.visible.single()))
        assertEquals("a1", session.state.value.current?.id)
        assertEquals(listOf("v1", "a1"), session.state.value.queue.map { it.id })
    }

    @Test
    fun browseShowsErrorAndRetries() = runTest {
        val library = FakeLibrary(fail = true)
        val vm = BrowseViewModel("uuid:nas", "0", "Root", FakeDevices(), BrowseFolderUseCase(library), FilterEntriesUseCase(), session)
        assertEquals("Server timed out", vm.uiState.value.error)
        library.fail = false
        vm.load()
        assertEquals(null, vm.uiState.value.error)
        assertEquals(4, vm.uiState.value.all.size)
    }

    @Test
    fun serverStartsOnlyWithPermissionAndRestartsOnChange() = runTest {
        val server = FakeServer()
        val settings = FakeSettings().apply { update { it.copy(autoStart = false) } }
        val vm = ServerViewModel(
            serverSession(server, settings),
            receiverSession(settings),
            airPlaySession(settings),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.start(permissionGranted = false)
        assertTrue(vm.uiState.value.permissionDenied)
        assertEquals(0, server.starts.size)

        vm.start(permissionGranted = true)
        assertIs<ServerStatus.Running>(vm.uiState.value.status)

        vm.setShareImage(true) // changes what is published → restart
        assertEquals(2, server.starts.size)
        assertTrue(MediaKind.IMAGE in server.starts.last().sharedKinds)

        vm.setAutoStart(false) // doesn't → no restart
        assertEquals(2, server.starts.size)
    }

    @Test
    fun receiverToggleStartsAndStopsReceiving() = runTest {
        val server = FakeServer()
        val settings = FakeSettings()
        val receiver = FakeReceiver()
        val receiving = receiverSession(settings, receiver).also { it.start() }
        val vm = ServerViewModel(
            serverSession(server, settings),
            receiving,
            airPlaySession(settings),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(ReceiverStatus.Stopped, vm.uiState.value.receiver) // off by default
        val starts = server.starts.size

        vm.setReceiverEnabled(true)
        assertEquals(ReceiverStatus.Running("Worawit's MacBook"), vm.uiState.value.receiver)
        assertTrue(settings.serverSettings.value.receiverEnabled)
        assertEquals(starts, server.starts.size) // the DLNA server is unaffected

        vm.setReceiverEnabled(false)
        assertEquals(ReceiverStatus.Stopped, vm.uiState.value.receiver)
    }

    @Test
    fun airPlayToggleAndPinSetting() = runTest {
        val server = FakeServer()
        val settings = FakeSettings()
        val airPlay = FakeAirPlay()
        airPlaySession(settings, airPlay).start()
        val vm = ServerViewModel(
            serverSession(server, settings),
            receiverSession(settings), airPlaySession(settings, airPlay),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(AirPlayStatus.Stopped, vm.uiState.value.airPlay) // off by default
        assertTrue(settings.serverSettings.value.airPlayRequirePin) // code required by default
        val starts = server.starts.size

        vm.setAirPlayEnabled(true)
        assertEquals(AirPlayStatus.Running("Worawit's MacBook"), vm.uiState.value.airPlay)
        vm.setAirPlayRequirePin(false)
        assertFalse(settings.serverSettings.value.airPlayRequirePin)
        assertEquals(starts, server.starts.size) // the DLNA server is unaffected
    }

    @Test
    fun shellOpensMirroringAndShowsThePin() = runTest {
        val airPlay = FakeAirPlay(AirPlayStatus.Running("TV"))
        val vm = ShellViewModel(
            FakeDevices(),
            serverSession(FakeServer(), FakeSettings()),
            session,
            receiverSession(),
            airPlaySession(airPlay = airPlay).also { it.start() },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val opened = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.openMirror.first() }

        airPlay.activity.value = AirPlayActivity(client = "Worawit's iPhone", pin = "4821")
        assertEquals("4821", vm.uiState.value.airPlay.pin)
        assertFalse(opened.isCompleted)

        airPlay.activity.value = AirPlayActivity(client = "Worawit's iPhone", mirroring = true)
        assertTrue(opened.isCompleted)
        assertEquals(null, vm.uiState.value.airPlay.pin)
    }

    @Test
    fun shellCombinesDevicesAndTarget() = runTest {
        val vm = ShellViewModel(
            FakeDevices(),
            serverSession(FakeServer(), FakeSettings()),
            session,
            receiverSession(),
            airPlaySession(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(listOf("Living Room NAS"), vm.uiState.value.servers.map { it.name })
        assertEquals(listOf("Samsung Q80 TV"), vm.uiState.value.renderers.map { it.name })
        vm.selectTarget(PlaybackTarget.Renderer(fakeTv))
        assertEquals(PlaybackTarget.Renderer(fakeTv), vm.uiState.value.target)
    }

    @Test
    fun aServerCanBeAddedByItsAddress() = runTest {
        val devices = FakeDevices()
        val nas = MediaDevice("uuid:nas2", "Office NAS", "Synology", "DS220", "192.168.1.30", null, isServer = true, isRenderer = false)
        devices.byAddress["192.168.1.30"] = nas
        val vm = ShellViewModel(
            devices,
            serverSession(FakeServer(), FakeSettings()),
            session,
            receiverSession(),
            airPlaySession(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.addServer("192.168.1.99")
        advanceUntilIdle()
        assertEquals(AddServerState.NotFound("192.168.1.99"), vm.uiState.value.addServer)
        vm.dismissAddServer()
        assertEquals(AddServerState.Idle, vm.uiState.value.addServer)

        vm.addServer(" 192.168.1.30 ")
        advanceUntilIdle()
        assertEquals(AddServerState.Added(listOf("Office NAS")), vm.uiState.value.addServer)
        assertTrue("Office NAS" in vm.uiState.value.servers.map { it.name })
        assertEquals(listOf("192.168.1.99", " 192.168.1.30 "), devices.asked)
        assertEquals(listOf("192.168.1.30"), vm.uiState.value.savedAddresses)

        // Removed again: no longer saved, and its server leaves the list.
        vm.forgetServer("192.168.1.30")
        advanceUntilIdle()
        assertEquals(emptyList(), vm.uiState.value.savedAddresses)
        assertTrue("Office NAS" !in vm.uiState.value.servers.map { it.name })

        // Cancel while it works: the search stops, and nothing turns up later.
        devices.slow = true
        vm.addServer("192.168.1.40")
        assertEquals(AddServerState.Working, vm.uiState.value.addServer)
        vm.dismissAddServer()
        advanceUntilIdle()
        assertEquals(AddServerState.Idle, vm.uiState.value.addServer)
    }

    @Test
    fun theMiniPlayerPlaysAndPausesOurPlayerOffScreen() = runTest {
        val vm = ShellViewModel(
            FakeDevices(),
            serverSession(FakeServer(), FakeSettings()),
            session,
            receiverSession(),
            airPlaySession(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, streamUrl = "http://h/a1.mp3")
        // Loaded but not started (or stopped): nothing to pause.
        session.play(song, listOf(song), autoPlay = false)
        assertEquals(null, vm.uiState.value.nowPlaying.playing)

        // Our player plays on off the player screen: the mini player plays and pauses it.
        vm.togglePause()
        assertEquals(true, vm.uiState.value.nowPlaying.playing)
        vm.togglePause()
        assertEquals(false, vm.uiState.value.nowPlaying.playing)
        assertEquals(listOf("load:a1@0", "play", "pause"), engine.calls)

        // Playing on a TV: the same button is the TV's play/pause (the session sends it there).
        vm.selectTarget(PlaybackTarget.Renderer(fakeTv))
        assertEquals(true, vm.uiState.value.nowPlaying.playing) // what the TV reports: it plays
    }

    @Test
    fun nextAndPreviousGoToTheSenderForItsAirPlayVideo() = runTest {
        airPlaySession().start()
        val vm = PlayerViewModel(session)
        val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, streamUrl = "http://h/a1.mp3")
        val song2 = MediaEntry("a2", "0", "Lam Phloen", MediaKind.AUDIO, streamUrl = "http://h/a2.mp3")

        // Our own queue: next is ours, even when a sender is connected.
        session.play(song, listOf(song, song2))
        airPlay.activity.value = AirPlayActivity(client = "iPhone", canControlSender = true)
        vm.next()
        assertEquals("a2", session.state.value.current?.id)
        assertTrue(airPlay.sentToSender.isEmpty())

        // The sender's AirPlay video is what plays: its queue (YouTube) decides.
        val video = MediaEntry("airplay-1", "-1", "AirPlay from iPhone", MediaKind.VIDEO, streamUrl = "http://localhost:7000/master.m3u8")
        airPlay.send(CastCommand.Load(video, autoPlay = true))
        airPlay.activity.value = AirPlayActivity(client = "iPhone", canControlSender = true, videoEntryId = "airplay-1")
        vm.next()
        vm.previous()
        assertEquals(listOf(SenderCommand.Next, SenderCommand.Previous), airPlay.sentToSender)
        // Its queue, not ours: no shuffle or repeat, and Next is there.
        val controls = assertIs<PlayerUi.Local>(vm.uiState.value).controls
        assertEquals(null, controls.modes)
        assertTrue(controls.nextEnabled)

        // Without remote control from that sender, nothing is sent.
        airPlay.activity.value = AirPlayActivity(client = "iPhone", canControlSender = false, videoEntryId = "airplay-1")
        vm.next()
        assertEquals(2, airPlay.sentToSender.size)
    }

    @Test
    fun subtitlesArePickedPerVideoAndKeptWhenThePlayerScreenComesBack() = runTest {
        val vm = PlayerViewModel(session)
        val thai = Subtitle("http://h/Movie.th.srt", "Thai", "th")
        val broken = Subtitle("http://h/Movie.broken.srt", "broken")
        val movie = MediaEntry("v1", "0", "Movie", MediaKind.VIDEO, streamUrl = "http://h/v1.mp4", subtitles = listOf(thai, broken))
        val next = MediaEntry("v2", "0", "Next", MediaKind.VIDEO, streamUrl = "http://h/v2.mp4")
        session.play(movie, listOf(movie, next))
        assertEquals(SubtitleState(), vm.subtitles.value) // off until picked

        // Read off the main thread: wait for it.
        vm.selectSubtitle(SubtitleChoice.File(broken))
        assertEquals("Couldn't load the subtitles (broken)", vm.subtitles.first { !it.loading }.error)
        vm.selectSubtitle(SubtitleChoice.File(thai))
        assertEquals(SubtitleState(SubtitleChoice.File(thai)), vm.subtitles.first { !it.loading })

        // The player screen closed and opened again: a new ViewModel, the same subtitles.
        val again = PlayerViewModel(session)
        assertEquals(SubtitleChoice.File(thai), again.subtitles.value.choice)

        // A track inside the video: the player shows it, and the file goes.
        val inside = TextTrack("2", "English", "eng")
        engine.facts = engine.facts.copy(textTracks = listOf(inside)) // the player has read the file's tracks
        again.selectSubtitle(SubtitleChoice.Track(inside))
        assertEquals(SubtitleState(SubtitleChoice.Track(inside)), again.subtitles.value)
        assertEquals("track:2", engine.calls.last())

        session.next() // another video: its own files, so off again
        assertEquals(SubtitleState(), again.subtitles.value)
    }

    @Test
    fun thePlayerScreenIsTheSameModelWhereverItPlays() = runTest {
        val vm = PlayerViewModel(session)
        assertEquals(PlayerUi.Empty, vm.uiState.value)
        val songs = (1..4).map { MediaEntry("a$it", "0", "Song $it", MediaKind.AUDIO, streamUrl = "http://h/a$it.mp3") }
        session.play(songs[0], songs)
        session.setShuffle(true)
        val here = assertIs<PlayerUi.Local>(vm.uiState.value).controls

        // On a TV: the same queue, in the same (shuffled) order, and the same Next.
        session.setTarget(PlaybackTarget.Renderer(fakeTv))
        val remote = assertIs<PlayerUi.Remote>(vm.uiState.value)
        assertEquals(here.upNext.map { it.entry.id }, remote.controls.upNext.map { it.entry.id })
        assertEquals(session.state.value.order, remote.controls.upNext.map { it.index })
        assertEquals(here.nextEnabled, remote.controls.nextEnabled)
        assertEquals(Modes(shuffle = true, repeat = RepeatMode.OFF), remote.controls.modes)
        // Loading on the TV: Play/Pause shows Pause, as tapping it pauses. (The TV is asked every second.)
        renderer.state = TransportState.TRANSITIONING
        val loading = withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.uiState.first { (it as? PlayerUi.Remote)?.status?.state == TransportState.TRANSITIONING } }
        }
        assertTrue(assertIs<PlayerUi.Remote>(loading).controls.playing)

        // A photo here: the viewer.
        session.setTarget(PlaybackTarget.Local)
        val photo = MediaEntry("p", "0", "Beach", MediaKind.IMAGE, streamUrl = "http://h/p.jpg")
        session.play(photo, listOf(photo))
        assertEquals(PlayerUi.Photos(listOf(photo), 0), vm.uiState.value)

        // A sender's music: its page; play/pause goes to the sender, from here and from the mini player alike.
        airPlay.activity.value = AirPlayActivity(client = "iPhone", audioOnly = true, canControlSender = true)
        val audio = assertIs<PlayerUi.SenderAudio>(vm.uiState.value).audio
        assertEquals(true, audio.playing)
        vm.togglePause()
        assertEquals(listOf(SenderCommand.PlayPause), airPlay.sentToSender)
        assertEquals(false, assertIs<PlayerUi.SenderAudio>(vm.uiState.value).audio.playing)
    }

    @Test
    fun theMiniPlayerPlaysAndPausesTheSendersMusic() = runTest {
        val vm = ShellViewModel(FakeDevices(), serverSession(FakeServer(), FakeSettings()), session, receiverSession(), airPlaySession())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        airPlay.activity.value = AirPlayActivity(client = "iPhone", audioOnly = true, canControlSender = true)
        assertEquals(true, vm.uiState.value.nowPlaying.playing) // a play/pause button, not just "open"
        vm.togglePause()
        assertEquals(listOf(SenderCommand.PlayPause), airPlay.sentToSender)
        assertEquals(false, vm.uiState.value.nowPlaying.playing)
        assertTrue(engine.calls.none { it == "play" }) // our own player stays paused
    }
}

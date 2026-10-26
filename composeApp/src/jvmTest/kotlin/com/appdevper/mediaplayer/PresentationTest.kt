package com.appdevper.mediaplayer

import com.appdevper.mediaplayer.data.di.dataModule
import com.appdevper.mediaplayer.data.di.platformDataModule
import com.appdevper.mediaplayer.di.domainModule
import com.appdevper.mediaplayer.di.platformAppModule
import com.appdevper.mediaplayer.di.presentationModule
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.first
import com.appdevper.mediaplayer.feature.mirror.MirrorViewModel
import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.usecase.BrowseFolderUseCase
import com.appdevper.mediaplayer.domain.usecase.FilterEntriesUseCase
import com.appdevper.mediaplayer.domain.usecase.GetDeviceUseCase
import com.appdevper.mediaplayer.domain.usecase.GetServerCapabilitiesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerSettingsUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerStatusUseCase
import com.appdevper.mediaplayer.domain.usecase.StartMediaServerUseCase
import com.appdevper.mediaplayer.domain.usecase.StopMediaServerUseCase
import com.appdevper.mediaplayer.domain.usecase.UpdateServerSettingsUseCase
import com.appdevper.mediaplayer.feature.browse.BrowseViewModel
import com.appdevper.mediaplayer.feature.player.PlayerViewModel
import com.appdevper.mediaplayer.feature.server.ServerViewModel
import com.appdevper.mediaplayer.feature.shell.ShellViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
    private val session = PlaybackSession(FakeRenderer(), appScope)

    private fun receiverSession(settings: FakeSettings = FakeSettings(), receiver: FakeReceiver = FakeReceiver()) =
        CastReceiverSession(receiver, settings, session, appScope)

    private fun airPlaySession(settings: FakeSettings = FakeSettings(), airPlay: FakeAirPlay = FakeAirPlay()) =
        AirPlayReceiverSession(airPlay, settings, session, appScope)

    @Test
    fun browseLoadsFiltersAndPlays() = runTest {
        val library = FakeLibrary()
        val vm = BrowseViewModel("uuid:nas", "0", "Root", GetDeviceUseCase(FakeDevices()), BrowseFolderUseCase(library), FilterEntriesUseCase(), session)
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
        val vm = BrowseViewModel("uuid:nas", "0", "Root", GetDeviceUseCase(FakeDevices()), BrowseFolderUseCase(library), FilterEntriesUseCase(), session)
        assertEquals("Server timed out", vm.uiState.value.error)
        library.fail = false
        vm.load()
        assertEquals(null, vm.uiState.value.error)
        assertEquals(4, vm.uiState.value.all.size)
    }

    @Test
    fun serverStartsOnlyWithPermissionAndRestartsOnChange() = runTest {
        val server = FakeServer()
        val settings = FakeSettings()
        val vm = ServerViewModel(
            ObserveServerStatusUseCase(server), ObserveServerSettingsUseCase(settings), UpdateServerSettingsUseCase(settings),
            StartMediaServerUseCase(server, settings), StopMediaServerUseCase(server), GetServerCapabilitiesUseCase(server),
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
            ObserveServerStatusUseCase(server), ObserveServerSettingsUseCase(settings), UpdateServerSettingsUseCase(settings),
            StartMediaServerUseCase(server, settings), StopMediaServerUseCase(server), GetServerCapabilitiesUseCase(server),
            receiving,
            airPlaySession(settings),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(ReceiverStatus.Stopped, vm.uiState.value.receiver) // off by default

        vm.setReceiverEnabled(true)
        assertEquals(ReceiverStatus.Running("Worawit's MacBook"), vm.uiState.value.receiver)
        assertTrue(settings.serverSettings.value.receiverEnabled)
        assertEquals(0, server.starts.size) // the DLNA server is unaffected

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
            ObserveServerStatusUseCase(server), ObserveServerSettingsUseCase(settings), UpdateServerSettingsUseCase(settings),
            StartMediaServerUseCase(server, settings), StopMediaServerUseCase(server), GetServerCapabilitiesUseCase(server),
            receiverSession(settings), airPlaySession(settings, airPlay),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(AirPlayStatus.Stopped, vm.uiState.value.airPlay) // off by default
        assertTrue(settings.serverSettings.value.airPlayRequirePin) // code required by default

        vm.setAirPlayEnabled(true)
        assertEquals(AirPlayStatus.Running("Worawit's MacBook"), vm.uiState.value.airPlay)
        vm.setAirPlayRequirePin(false)
        assertFalse(settings.serverSettings.value.airPlayRequirePin)
        assertEquals(0, server.starts.size) // the DLNA server is unaffected
    }

    @Test
    fun shellOpensMirroringAndShowsThePin() = runTest {
        val airPlay = FakeAirPlay(AirPlayStatus.Running("TV"))
        val vm = ShellViewModel(
            com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase(FakeDevices()),
            ObserveServerStatusUseCase(FakeServer()),
            com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase(FakeDevices()),
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
            com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase(FakeDevices()),
            ObserveServerStatusUseCase(FakeServer()),
            com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase(FakeDevices()),
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
}

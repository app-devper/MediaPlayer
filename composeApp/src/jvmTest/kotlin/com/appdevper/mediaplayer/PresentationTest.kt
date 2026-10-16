package com.appdevper.mediaplayer

import com.appdevper.mediaplayer.data.di.dataModule
import com.appdevper.mediaplayer.data.di.platformDataModule
import com.appdevper.mediaplayer.di.domainModule
import com.appdevper.mediaplayer.di.platformAppModule
import com.appdevper.mediaplayer.di.presentationModule
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
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
        assertNotNull(koin.get<BrowseViewModel> { parametersOf("uuid:x", "0", "Root") })
    }

    private val session = PlaybackSession(FakeRenderer(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

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
    fun shellCombinesDevicesAndTarget() = runTest {
        val vm = ShellViewModel(
            com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase(FakeDevices()),
            ObserveServerStatusUseCase(FakeServer()),
            com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase(FakeDevices()),
            session,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(listOf("Living Room NAS"), vm.uiState.value.servers.map { it.name })
        assertEquals(listOf("Samsung Q80 TV"), vm.uiState.value.renderers.map { it.name })
        vm.selectTarget(PlaybackTarget.Renderer(fakeTv))
        assertEquals(PlaybackTarget.Renderer(fakeTv), vm.uiState.value.target)
    }
}

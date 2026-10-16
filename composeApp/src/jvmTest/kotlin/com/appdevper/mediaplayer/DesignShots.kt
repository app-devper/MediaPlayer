package com.appdevper.mediaplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appdevper.mediaplayer.di.domainModule
import com.appdevper.mediaplayer.di.presentationModule
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.LibraryRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import com.appdevper.mediaplayer.ui.shell.AppShell
import com.appdevper.mediaplayer.ui.shell.BrowseRoute
import com.appdevper.mediaplayer.ui.shell.HomeRoute
import com.appdevper.mediaplayer.ui.shell.PlayerRoute
import com.appdevper.mediaplayer.ui.shell.ServerRoute
import com.appdevper.mediaplayer.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DesignShots {
    private val out = System.getenv("SHOT_DIR")

    private fun shot(name: String, w: Int, h: Int, dark: Boolean, setup: (PlaybackSession) -> Unit = {}, start: Any = HomeRoute) {
        if (out == null) return
        val server = FakeServer().also { runBlocking { it.start(FakeSettings().serverSettings.value) } }
        val koin = startKoin {
            modules(
                module {
                    single<CoroutineScope>(named("appScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                    single<DeviceRepository> { FakeDevices() }
                    single<LibraryRepository> { FakeLibrary() }
                    single<RendererRepository> { FakeRenderer() }
                    single<SettingsRepository> { FakeSettings() }
                    single<MediaServerRepository> { server }
                },
                domainModule, presentationModule,
            )
        }.koin
        setup(koin.get())
        try {
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(w.dp, h.dp)) {
                        AppTheme(darkTheme = dark) { AppShell(start) }
                    }
                }
                waitForIdle()
                val bmp = onRoot().captureToImage().asSkiaBitmap()
                File(out, "$name.png").writeBytes(Image.makeFromBitmap(bmp).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        } finally {
            stopKoin()
        }
    }

    private val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, artist = "Bodyslam", album = "Kraam", streamUrl = "http://h/a1.mp3", durationMs = 245_000)
    private val song2 = MediaEntry("a2", "0", "Lam Phloen", MediaKind.AUDIO, artist = "Bodyslam", streamUrl = "http://h/a2.mp3", durationMs = 201_000)

    @Test fun homePhoneLight() = shot("1-home-phone-light", 400, 860, dark = false)
    @Test fun homeDesktopDark() = shot("2-home-desktop-dark", 1280, 800, dark = true)
    @Test fun browsePhoneLight() = shot("3-browse-phone-light", 400, 860, dark = false, start = BrowseRoute("uuid:nas", "0", "Living Room NAS"))
    @Test fun remoteDesktopLight() = shot("4-remote-desktop-light", 1280, 860, dark = false, setup = { s ->
        s.setTarget(PlaybackTarget.Renderer(fakeTv))
        s.play(song, listOf(song, song2))
    }, start = PlayerRoute)
    @Test fun serverPhoneDark() = shot("5-server-phone-dark", 400, 1000, dark = true, start = ServerRoute)
}

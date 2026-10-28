package com.appdevper.mediaplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.di.domainModule
import com.appdevper.mediaplayer.di.presentationModule
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayNowPlaying
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.appdevper.mediaplayer.playback.FfmpegPlaybackEngine
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.LibraryRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import com.appdevper.mediaplayer.ui.shell.AppShell
import com.appdevper.mediaplayer.ui.shell.BrowseRoute
import com.appdevper.mediaplayer.ui.shell.HomeRoute
import com.appdevper.mediaplayer.ui.shell.MirrorRoute
import com.appdevper.mediaplayer.ui.shell.PlayerRoute
import com.appdevper.mediaplayer.ui.shell.ServerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DesignShots {
    private val out = System.getenv("SHOT_DIR")

    /** Renders the app to SHOT_DIR; with [always] it also runs without SHOT_DIR (for [act]'s checks). */
    private fun shot(
        name: String, w: Int, h: Int, dark: Boolean, setup: (PlaybackSession) -> Unit = {}, start: Any = HomeRoute,
        airPlay: FakeAirPlay = FakeAirPlay(AirPlayStatus.Running("Worawit's MacBook")),
        always: Boolean = false,
        act: ComposeUiTest.() -> Unit = {},
    ) {
        if (out == null && !always) return
        val server = FakeServer().also { runBlocking { it.start(FakeSettings().serverSettings.value) } }
        val koin = startKoin {
            modules(
                module {
                    single<CoroutineScope>(named("appScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                    single<DeviceRepository> { FakeDevices() }
                    single<LibraryRepository> { FakeLibrary() }
                    single<RendererRepository> { FakeRenderer() }
                    single<SettingsRepository> { FakeSettings().apply { update { it.copy(receiverEnabled = true, airPlayEnabled = true) } } }
                    single<MediaServerRepository> { server }
                    single<CastReceiverRepository> { FakeReceiver().apply { status.value = ReceiverStatus.Running("Worawit's MacBook") } }
                    single<AirPlayRepository> { airPlay }
                    single { AirPlayPlayer() }
                    single { FfmpegPlaybackEngine() } bind PlaybackEngine::class
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
                act()
                waitForIdle()
                if (out != null) {
                    val bmp = onRoot().captureToImage().asSkiaBitmap()
                    File(out, "$name.png").writeBytes(Image.makeFromBitmap(bmp).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        } finally {
            stopKoin()
        }
    }

    private val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, artist = "Bodyslam", album = "Kraam", streamUrl = "http://h/a1.mp3", durationMs = 245_000)
    private val song2 = MediaEntry("a2", "0", "Lam Phloen", MediaKind.AUDIO, artist = "Bodyslam", streamUrl = "http://h/a2.mp3", durationMs = 201_000)

    private val clip = MediaEntry("v1", "0", "Weekend in Chiang Mai", MediaKind.VIDEO, streamUrl = "http://127.0.0.1:9/v1.mp4", durationMs = 754_000)

    @Test fun videoLocalDesktopDark() = shot("10-video-local-desktop-dark", 1280, 800, dark = true, setup = { s ->
        s.play(clip, listOf(clip), autoPlay = false)
    }, start = PlayerRoute, always = true) {
        // Labelled controls over the picture (the stream can't load in tests, so no timing checks).
        onNodeWithContentDescription("Play").assertExists()
        onNodeWithContentDescription("Full screen").assertExists()
    }
    @Test fun songLocalPhoneLight() = shot("11-song-local-phone-light", 400, 860, dark = false, setup = { s ->
        s.play(song, listOf(song, song2), autoPlay = false)
    }, start = PlayerRoute)

    @Test fun homePhoneLight() = shot("1-home-phone-light", 400, 860, dark = false)
    @Test fun homeDesktopDark() = shot("2-home-desktop-dark", 1280, 800, dark = true)
    @Test fun browsePhoneLight() = shot("3-browse-phone-light", 400, 860, dark = false, start = BrowseRoute("uuid:nas", "0", "Living Room NAS"))
    @Test fun remoteDesktopLight() = shot("4-remote-desktop-light", 1280, 860, dark = false, setup = { s ->
        s.setTarget(PlaybackTarget.Renderer(fakeTv))
        s.play(song, listOf(song, song2))
    }, start = PlayerRoute, always = true) {
        // First song: "Previous" is one node with its name, announced as a disabled button.
        onNodeWithContentDescription("Previous").assertIsNotEnabled()
        onNodeWithContentDescription("Next").assertIsEnabled()
    }
    @Test fun serverPhoneDark() = shot("5-server-phone-dark", 400, 1100, dark = true, start = ServerRoute, always = true) {
        // A switch row is one toggle that carries its label and state, and activating it (as a screen
        // reader does; the row is below this test window, so no pointer click) toggles the setting.
        val videos = onNode(hasText("Videos") and isToggleable())
        videos.assertIsOn()
        videos.performSemanticsAction(SemanticsActions.OnClick)
        // The setting is saved through the ViewModel, then comes back to the screen.
        waitUntil(timeoutMillis = 5_000) { runCatching { videos.assertIsOff() }.isSuccess }
        videos.performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { runCatching { videos.assertIsOn() }.isSuccess }
    }
    @Test fun mirrorWaitingDesktopDark() = shot("6-mirror-waiting-desktop-dark", 1280, 800, dark = true, start = MirrorRoute)
    @Test fun mirrorFullscreenDesktopDark() = shot(
        "8-mirror-fullscreen-desktop-dark", 1280, 800, dark = true, start = MirrorRoute, always = true,
        airPlay = FakeAirPlay(AirPlayStatus.Running("Worawit's MacBook")).apply {
            activity.value = AirPlayActivity(client = "Worawit's iPhone", mirroring = true)
        },
    ) {
        onNodeWithText("Home").assertExists() // sidebar
        onNodeWithContentDescription("Full screen").performClick()
        waitForIdle()
        onNodeWithText("Home").assertDoesNotExist() // app chrome hidden
        onNodeWithText("Mirroring Worawit's iPhone").assertDoesNotExist()
        onNodeWithContentDescription("Exit full screen").assertExists()
        onNodeWithContentDescription("Exit full screen").performClick()
        waitForIdle()
        onNodeWithText("Home").assertExists()
        onNodeWithContentDescription("Full screen").performClick() // leave it full screen for the picture
    }

    private val music = FakeAirPlay(AirPlayStatus.Running("Worawit's MacBook"))

    @Test fun airPlayMusicDesktopDark() = shot(
        "9-airplay-music-desktop-dark", 1280, 800, dark = true, start = PlayerRoute, always = true,
        airPlay = music.apply {
            activity.value = AirPlayActivity(
                client = "Worawit's iPhone", audioOnly = true, canControlSender = true,
                nowPlaying = AirPlayNowPlaying("Khon Kaen Blues", "Bodyslam", "Kraam", cover(), positionMs = 83_000, durationMs = 245_000),
            )
        },
    ) {
        onAllNodesWithText("Khon Kaen Blues").assertCountEquals(2) // player + sidebar "Now playing"
        onNodeWithText("Bodyslam · Kraam").assertExists()
        onNodeWithText("Playing from Worawit's iPhone").assertExists()
        onNodeWithText("4:05").assertExists()
        onNodeWithText("Mirroring Worawit's iPhone").assertDoesNotExist()
        // The sender takes remote control: the buttons go to its app.
        onNodeWithContentDescription("Next").performClick()
        onNodeWithContentDescription("Previous").performClick()
        onNodeWithContentDescription("Pause").performClick()
        onNodeWithContentDescription("Play").assertExists() // follows the tap
        waitUntil(timeoutMillis = 5_000) { music.sentToSender.size == 3 }
        assertEquals(listOf(SenderCommand.Next, SenderCommand.Previous, SenderCommand.PlayPause), music.sentToSender)
    }

    /** A gradient JPEG standing in for cover art from the sender. */
    private fun cover(): ByteArray {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.drawPaint(Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 300f, 300f, intArrayOf(0xffe8505b.toInt(), 0xff2d1b69.toInt()))
        })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG)!!.bytes
    }

    @Test fun airPlayPinPhoneLight() = shot(
        "7-airplay-pin-phone-light", 400, 860, dark = false,
        airPlay = FakeAirPlay(AirPlayStatus.Running("Worawit's MacBook")).apply {
            activity.value = AirPlayActivity(client = "Worawit's iPhone", pin = "4821")
        },
    )
}

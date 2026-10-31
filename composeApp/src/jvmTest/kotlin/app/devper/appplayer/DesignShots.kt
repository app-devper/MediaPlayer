package app.devper.appplayer

import app.devper.appplayer.testing.FakeServer
import app.devper.appplayer.testing.FakeSettings
import app.devper.appplayer.testing.FakeRenderer
import app.devper.appplayer.testing.FakeAirPlay
import app.devper.appplayer.testing.FakeReceiver
import app.devper.appplayer.testing.FakeSubtitles
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
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
import kotlin.test.assertTrue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.devper.appplayer.airplay.AirPlayPlayer
import app.devper.appplayer.designsystem.theme.AppTheme
import app.devper.appplayer.di.domainModule
import app.devper.appplayer.di.presentationModule
import app.devper.appplayer.domain.model.AirPlayActivity
import app.devper.appplayer.domain.model.AirPlayNowPlaying
import app.devper.appplayer.domain.model.AirPlayStatus
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.PlaybackTarget
import app.devper.appplayer.domain.model.ReceiverStatus
import app.devper.appplayer.domain.model.SenderCommand
import app.devper.appplayer.domain.session.PlaybackEngine
import app.devper.appplayer.domain.session.PlaybackSession
import app.devper.appplayer.playback.FfmpegPlaybackEngine
import app.devper.appplayer.domain.repository.AirPlayRepository
import app.devper.appplayer.domain.repository.CastReceiverRepository
import app.devper.appplayer.domain.repository.DeviceRepository
import app.devper.appplayer.domain.repository.LibraryRepository
import app.devper.appplayer.domain.repository.MediaServerRepository
import app.devper.appplayer.domain.repository.RendererRepository
import app.devper.appplayer.domain.repository.SettingsRepository
import app.devper.appplayer.domain.repository.SubtitleRepository
import app.devper.appplayer.ui.shell.AppShell
import app.devper.appplayer.ui.shell.BrowseRoute
import app.devper.appplayer.ui.shell.HomeRoute
import app.devper.appplayer.ui.shell.MirrorRoute
import app.devper.appplayer.ui.shell.PlayerRoute
import app.devper.appplayer.ui.shell.ServerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
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
                    single<RendererRepository> { FakeRenderer(position = 83_000, duration = 245_000, volume = 35) }
                    single<SettingsRepository> { FakeSettings().apply { update { it.copy(receiverEnabled = true, airPlayEnabled = true) } } }
                    single<MediaServerRepository> { server }
                    single<CastReceiverRepository> { FakeReceiver().apply { status.value = ReceiverStatus.Running("Worawit's MacBook") } }
                    single<AirPlayRepository> { airPlay }
                    single<SubtitleRepository> { FakeSubtitles() }
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
    }, start = PlayerRoute, always = true) {
        // Shuffle and repeat say what they're set to; repeat goes off → all → one.
        fun state(name: String, value: String) =
            onNodeWithContentDescription(name).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
        state("Shuffle", "Off")
        state("Repeat", "Off")
        onNodeWithContentDescription("Shuffle").performClick()
        onNodeWithContentDescription("Repeat").performClick()
        state("Shuffle", "On")
        state("Repeat", "All")
        onNodeWithContentDescription("Repeat").performClick()
        state("Repeat", "One")
    }

    @Test fun homePhoneLight() = shot("1-home-phone-light", 400, 860, dark = false)
    @Test fun homeDesktopDark() = shot("2-home-desktop-dark", 1280, 800, dark = true)
    @Test fun browsePhoneLight() = shot("3-browse-phone-light", 400, 860, dark = false, setup = { s ->
        s.play(song, listOf(song), autoPlay = false)
    }, start = BrowseRoute("uuid:nas", "0", "Living Room NAS"), always = true) {
        // Something playing: the mini player sits under the search field, not over it.
        val search = onNodeWithText("Search in Living Room NAS").fetchSemanticsNode().boundsInRoot
        val mini = onNodeWithContentDescription("Open player").fetchSemanticsNode().boundsInRoot
        assertTrue(search.bottom <= mini.top, "search $search, mini player $mini")
    }
    @Test fun remoteDesktopLight() = shot("4-remote-desktop-light", 1280, 860, dark = false, setup = { s ->
        s.setTarget(PlaybackTarget.Renderer(fakeTv))
        s.play(song, listOf(song, song2))
    }, start = PlayerRoute, always = true) {
        // First song: "Previous" is one node with its name, still enabled (it goes back to the start).
        onNodeWithContentDescription("Previous").assertIsEnabled()
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
        onNodeWithText("From Worawit's iPhone").assertExists()
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

    /** A two-tone JPEG standing in for cover art from the sender (plain fills: the gradient API moves between Skiko releases). */
    private fun cover(): ByteArray {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(0xffe8505b.toInt())
        surface.canvas.drawRect(Rect.makeXYWH(0f, 150f, 300f, 150f), Paint().apply { color = 0xff2d1b69.toInt() })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG)!!.bytes
    }

    @Test fun airPlayPinPhoneLight() = shot(
        "7-airplay-pin-phone-light", 400, 860, dark = false,
        airPlay = FakeAirPlay(AirPlayStatus.Running("Worawit's MacBook")).apply {
            activity.value = AirPlayActivity(client = "Worawit's iPhone", pin = "4821")
        },
    )
}

package com.appdevper.mediaplayer

import com.appdevper.mediaplayer.testing.FakeEngine
import com.appdevper.mediaplayer.testing.FakeRenderer
import com.appdevper.mediaplayer.testing.playbackSession
import com.appdevper.mediaplayer.feature.player.PlayerScreen
import com.appdevper.mediaplayer.feature.player.PlayerViewModel
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleChoice
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.playback.FfmpegPlaybackEngine
import com.appdevper.mediaplayer.ui.player.LocalPlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared player view drives the local player, whatever engine is behind it (here a fake one). */
@OptIn(ExperimentalTestApi::class)
class LocalPlayerViewTest {

    private val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, streamUrl = "http://h/a1.mp3")
    private val clip = MediaEntry("v1", "0", "Weekend in Chiang Mai", MediaKind.VIDEO, streamUrl = "http://h/v1.mp4")

    @Test
    fun aSongOnThisDeviceHasTheSongLayoutsControls() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), scope, engine)
        try {
            session.play(song, listOf(song), autoPlay = false)
            setContent { AppTheme { PlayerScreen(PlayerViewModel(session)) } }
            onNodeWithText("Khon Kaen Blues").assertExists()
            onNodeWithContentDescription("Play").performClick()
            waitForIdle()
            onNodeWithContentDescription("Pause").performClick()
            waitForIdle()
            onNodeWithContentDescription("Play").assertExists()
            assertEquals(listOf("load:a1@0", "play", "pause"), engine.calls)

            // Desktop has no volume keys: mute is here (phones use theirs).
            onNodeWithContentDescription("Mute (M)").performClick()
            waitForIdle()
            assertEquals(0f, engine.gain)
            onNodeWithContentDescription("Unmute (M)").assertExists()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clickingTheVideoPlaysAndPausesEachTime() {
        // The desktop picture comes from the app's FFmpeg engine; nothing is loaded in it here.
        startKoin { modules(module { single { FfmpegPlaybackEngine() } }) }
        try {
            runComposeUiTest {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val engine = FakeEngine()
                val player = LocalPlayer(engine, scope)
                try {
                    player.load(clip, autoPlay = true, startPositionMs = 0)
                    setContent {
                        AppTheme {
                            LocalPlayerView(player, clip.title, volume = 100, muted = false, onVolumeChange = {}, onMutedChange = {})
                        }
                    }
                    repeat(2) {
                        onNodeWithContentDescription(clip.title).performClick()
                        waitForIdle()
                        mainClock.advanceTimeBy(500) // past a double-click
                        waitForIdle()
                    }
                    assertEquals(listOf("load:v1@0", "pause", "play"), engine.calls)
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun aSubtitleFilesLineFollowsThePosition() {
        startKoin { modules(module { single { FfmpegPlaybackEngine() } }) }
        try {
            runComposeUiTest {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val engine = FakeEngine()
                val files = object : SubtitleRepository {
                    override suspend fun fetch(url: String) =
                        "1\n00:00:01,000 --> 00:00:02,000\nHello there\n\n2\n00:00:05,000 --> 00:00:06,000\nLater\n".encodeToByteArray()
                }
                val player = LocalPlayer(engine, scope, pollIntervalMs = 50, subtitleFiles = files)
                try {
                    player.load(clip, autoPlay = false, startPositionMs = 1_500)
                    player.selectSubtitle(SubtitleChoice.File(Subtitle("http://h/v1.srt", "v1.srt")))
                    setContent {
                        AppTheme {
                            LocalPlayerView(player, clip.title, volume = 100, muted = false, onVolumeChange = {}, onMutedChange = {})
                        }
                    }
                    waitUntil(timeoutMillis = 2_000) { onAllNodesWithText("Hello there").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Hello there").assertExists()
                    onNodeWithText("Later").assertDoesNotExist()
                    player.seek(5_500)
                    waitUntil(timeoutMillis = 2_000) { onAllNodesWithText("Later").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Hello there").assertDoesNotExist()
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun aTrackInsideTheVideoShowsThePlayersText() {
        startKoin { modules(module { single { FfmpegPlaybackEngine() } }) }
        try {
            runComposeUiTest {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val engine = FakeEngine()
                val player = LocalPlayer(engine, scope, pollIntervalMs = 50)
                try {
                    player.load(clip, autoPlay = false, startPositionMs = 0)
                    setContent {
                        AppTheme {
                            LocalPlayerView(player, clip.title, volume = 100, muted = false, onVolumeChange = {}, onMutedChange = {})
                        }
                    }
                    val english = TextTrack("0", "English", "en")
                    engine.facts = engine.facts.copy(textTracks = listOf(english))
                    player.selectSubtitle(SubtitleChoice.Track(english))
                    engine.facts = engine.facts.copy(subtitleText = "From the file")
                    waitUntil(timeoutMillis = 2_000) { onAllNodesWithText("From the file").fetchSemanticsNodes().isNotEmpty() }
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun withTouchATapShowsTheControlsAndThePlayButtonPlays() {
        startKoin { modules(module { single { FfmpegPlaybackEngine() } }) }
        try {
            runComposeUiTest {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val engine = FakeEngine()
                val player = LocalPlayer(engine, scope)
                try {
                    player.load(clip, autoPlay = false, startPositionMs = 0)
                    setContent {
                        AppTheme {
                            LocalPlayerView(player, clip.title, volume = 100, muted = false, onVolumeChange = {}, onMutedChange = {}, mouse = false)
                        }
                    }
                    // Touch: a tap on the picture (off the big button in the middle) doesn't play; the button does.
                    onNodeWithContentDescription(clip.title).performTouchInput { click(Offset(10f, 10f)) }
                    waitForIdle()
                    assertEquals(listOf("load:v1@0"), engine.calls)
                    onAllNodesWithContentDescription("Play").onFirst().performClick()
                    waitForIdle()
                    assertEquals(listOf("load:v1@0", "play"), engine.calls)
                    // No volume slider on the bar: phones have volume keys.
                    onAllNodesWithContentDescription("Mute (M)").assertCountEquals(0)
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            stopKoin()
        }
    }
}

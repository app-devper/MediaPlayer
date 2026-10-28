package com.appdevper.mediaplayer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.SubtitleCue
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
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared player view drives the local player, whatever engine is behind it (here a fake one). */
@OptIn(ExperimentalTestApi::class)
class LocalPlayerViewTest {

    private val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, streamUrl = "http://h/a1.mp3")
    private val clip = MediaEntry("v1", "0", "Weekend in Chiang Mai", MediaKind.VIDEO, streamUrl = "http://h/v1.mp4")

    @Test
    fun theSongControlsPlayPauseAndSetTheVolume() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = FakeEngine()
        val player = LocalPlayer(engine, scope)
        val volumes = mutableListOf<String>()
        try {
            player.load(song, autoPlay = false, startPositionMs = 0)
            setContent {
                AppTheme {
                    LocalPlayerView(
                        player = player,
                        title = song.title,
                        isAudio = true,
                        volume = 60,
                        muted = false,
                        onVolumeChange = { volumes += "volume:$it" },
                        onMutedChange = { volumes += "muted:$it" },
                    )
                }
            }
            onNodeWithContentDescription("Play").performClick()
            waitForIdle()
            onNodeWithContentDescription("Pause").performClick()
            waitForIdle()
            onNodeWithContentDescription("Play").assertExists()
            assertEquals(listOf("load:a1", "play", "pause"), engine.calls)

            // Desktop's bar has a mute button (phones use their volume keys).
            onNodeWithContentDescription("Mute (M)").performClick()
            assertEquals(listOf("muted:true"), volumes)
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
                            LocalPlayerView(player, clip.title, isAudio = false, volume = 100, muted = false, onVolumeChange = {}, onMutedChange = {})
                        }
                    }
                    repeat(2) {
                        onNodeWithContentDescription(clip.title).performClick()
                        waitForIdle()
                        mainClock.advanceTimeBy(500) // past a double-click
                        waitForIdle()
                    }
                    assertEquals(listOf("load:v1", "pause", "play"), engine.calls)
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun theSubtitleLineOnScreenFollowsThePosition() {
        startKoin { modules(module { single { FfmpegPlaybackEngine() } }) }
        try {
            runComposeUiTest {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val engine = FakeEngine()
                val player = LocalPlayer(engine, scope)
                try {
                    player.load(clip, autoPlay = false, startPositionMs = 1_500)
                    val cues = listOf(SubtitleCue(1_000, 2_000, "Hello there"), SubtitleCue(5_000, 6_000, "Later"))
                    setContent {
                        AppTheme {
                            LocalPlayerView(player, clip.title, isAudio = false, volume = 100, muted = false, onVolumeChange = {}, onMutedChange = {}, cues = cues)
                        }
                    }
                    onNodeWithText("Hello there").assertExists()
                    onNodeWithText("Later").assertDoesNotExist()
                    player.seek(5_500)
                    waitForIdle()
                    onNodeWithText("Later").assertExists()
                    onNodeWithText("Hello there").assertDoesNotExist()
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            stopKoin()
        }
    }
}

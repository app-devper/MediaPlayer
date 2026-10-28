package com.appdevper.mediaplayer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.ui.player.LocalPlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared player view drives the local player, whatever engine is behind it (here a fake one). */
@OptIn(ExperimentalTestApi::class)
class LocalPlayerViewTest {

    private val song = MediaEntry("a1", "0", "Khon Kaen Blues", MediaKind.AUDIO, streamUrl = "http://h/a1.mp3")

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
}

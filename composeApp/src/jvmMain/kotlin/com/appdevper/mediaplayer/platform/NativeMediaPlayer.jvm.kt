package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.ui.icons.AppIcons
import com.appdevper.mediaplayer.ui.components.formatClock
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import java.awt.Desktop
import java.net.URI
import javax.swing.SwingUtilities

private val vlcAvailable: Boolean by lazy { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }

/** Desktop player backed by libVLC (vlcj). Falls back to the system player when VLC isn't installed. */
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier,
) {
    if (!vlcAvailable) {
        Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Install VLC (videolan.org) to play media inside the app.", color = Color.White)
            Button(onClick = { runCatching { Desktop.getDesktop().browse(URI(url)) } }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Open in default player")
            }
        }
        return
    }

    var playing by remember { mutableStateOf(false) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableFloatStateOf(100f) }
    val ended by rememberUpdatedState(onEnded)

    val component = remember { CallbackMediaPlayerComponent() }
    DisposableEffect(url) {
        val listener = object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { playing = true }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { timeMs = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { lengthMs = newLength }
            override fun finished(mediaPlayer: MediaPlayer) {
                playing = false
                // Never re-enter libVLC from its own event thread.
                SwingUtilities.invokeLater { ended() }
            }
        }
        val mp = component.mediaPlayer()
        mp.events().addMediaPlayerEventListener(listener)
        mp.media().play(url)
        onDispose {
            mp.events().removeMediaPlayerEventListener(listener)
            mp.controls().stop()
            component.release()
        }
    }

    val onVideoSlider = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
    )
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!isAudio) SwingPanel(factory = { component }, modifier = Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { component.mediaPlayer().controls().pause() }) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, "Play/Pause", tint = Color.White)
            }
            Text(formatClock(timeMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = seeking ?: if (lengthMs > 0) timeMs.toFloat() / lengthMs else 0f,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { component.mediaPlayer().controls().setTime((it * lengthMs).toLong()) }
                    seeking = null
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = onVideoSlider,
            )
            Text(formatClock(lengthMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Icon(AppIcons.Volume, null, tint = Color.White, modifier = Modifier.padding(start = 8.dp))
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    component.mediaPlayer().audio().setVolume(it.toInt())
                },
                valueRange = 0f..100f,
                modifier = Modifier.padding(start = 4.dp).width(120.dp),
                colors = onVideoSlider,
            )
        }
    }
}

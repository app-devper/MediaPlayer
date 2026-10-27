@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.usesExternalPlaybackWhileExternalScreenIsActive
import platform.AVFoundation.volume
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.URLQueryAllowedCharacterSet
import platform.Foundation.create
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.UIKit.accessibilityElementsHidden

private fun toNsUrl(url: String): NSURL? =
    NSURL.URLWithString(url) ?: NSString.create(string = url).stringByAddingPercentEncodingWithAllowedCharacters(
        NSCharacterSet.URLQueryAllowedCharacterSet,
    )?.let { NSURL.URLWithString(it) }

/**
 * AVPlayerViewController — native controls, Picture-in-Picture and the built-in AirPlay button.
 * External playback is enabled so video moves to the Apple TV when an AirPlay route is picked.
 */
@Composable
actual fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier,
    autoPlay: Boolean,
    startPositionMs: Long,
    volume: Float,
    commands: Flow<LocalPlayerCommand>,
    onStatus: (RendererStatus) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
) {
    val ended by rememberUpdatedState(onEnded)
    val report by rememberUpdatedState(onStatus)
    // "Stopped" = loaded but not started, finished, or stopped by a controller; reported as STOPPED rather than PAUSED.
    var stopped by remember(url) { mutableStateOf(!autoPlay) }
    val player = remember(url) {
        (toNsUrl(url)?.let { AVPlayer.playerWithURL(it) } ?: AVPlayer()).apply {
            allowsExternalPlayback = true
            usesExternalPlaybackWhileExternalScreenIsActive = true
        }
    }
    DisposableEffect(player) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = player.currentItem,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            stopped = true
            ended()
        }
        if (startPositionMs > 0) player.seekToMs(startPositionMs)
        if (autoPlay) player.play()
        onDispose {
            report(player.status(stopped = true))
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }
    LaunchedEffect(player) {
        commands.collect { cmd ->
            when (cmd) {
                LocalPlayerCommand.Play -> {
                    if (stopped && player.atEnd()) player.seekToMs(0)
                    stopped = false
                    player.play()
                }
                LocalPlayerCommand.Pause -> player.pause()
                LocalPlayerCommand.Stop -> {
                    stopped = true
                    player.pause()
                    player.seekToMs(0)
                }
                is LocalPlayerCommand.Seek -> player.seekToMs(cmd.positionMs)
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            // Started from the native controls also counts as playing.
            if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) stopped = false
            report(player.status(stopped))
            delay(STATUS_INTERVAL_MS)
        }
    }
    LaunchedEffect(player, volume) { player.volume = volume.coerceIn(0f, 1f) }
    val playerView: @Composable (Modifier) -> Unit = { viewModifier ->
        UIKitViewController(
            factory = {
                AVPlayerViewController().apply {
                    this.player = player
                    // Songs: our bar draws the controls; this unseen view is only kept for now playing.
                    showsPlaybackControls = !isAudio
                    allowsPictureInPicturePlayback = !isAudio
                    updatesNowPlayingInfoCenter = true
                    if (isAudio) view.accessibilityElementsHidden = true
                }
            },
            modifier = viewModifier,
        )
    }
    if (isAudio) {
        // Songs: our control bar, as on the other platforms. AVKit's controls need more height than
        // the song page gives them and were cut off; its view stays (unseen) for the lock screen's
        // now playing info.
        var shown by remember(player) { mutableStateOf(player.status(stopped)) }
        var error by remember(player) { mutableStateOf<String?>(null) }
        // Only while the app is in front (the status loop above keeps reporting in the background).
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(player, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    shown = player.status(stopped)
                    error = player.currentItem?.takeIf { it.status == AVPlayerItemStatusFailed }?.error?.localizedDescription
                    delay(CONTROLS_POLL_MS)
                }
            }
        }
        val playing = !stopped && player.timeControlStatus != AVPlayerTimeControlStatusPaused
        fun togglePlay() {
            if (playing) {
                player.pause()
            } else {
                if (stopped && player.atEnd()) player.seekToMs(0)
                stopped = false
                player.play()
            }
            shown = player.status(stopped)
        }
        val c = AppTheme.colors
        Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Text(error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    shown.state == TransportState.TRANSITIONING ->
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.textSecondary)
                }
            }
            PlayerControlBar(
                playing = playing,
                positionMs = shown.positionMs,
                durationMs = shown.durationMs,
                onPlayPause = ::togglePlay,
                onSeek = {
                    stopped = false
                    player.seekToMs(it)
                    shown = player.status(stopped)
                },
                content = c.textPrimary,
            ) {
                // AVKit's controls had the AirPlay button; songs keep one here.
                AirPlayButton(Modifier.size(40.dp), tint = c.textPrimary)
            }
            playerView(Modifier.size(1.dp).alpha(0f))
        }
        return
    }
    playerView(modifier)
}

private const val CONTROLS_POLL_MS = 200L

private fun Double.toMs(): Long = if (isNaN() || isInfinite() || this < 0) 0 else (this * 1000).toLong()

private fun AVPlayer.positionMs(): Long = CMTimeGetSeconds(currentTime()).toMs()

private fun AVPlayer.durationMs(): Long = currentItem?.let { CMTimeGetSeconds(it.duration).toMs() } ?: 0

/** Finished (a known duration, and at its end): Play starts it over. A stream of unknown length never is. */
private fun AVPlayer.atEnd(): Boolean = durationMs() > 0 && positionMs() >= durationMs() - 500

private fun AVPlayer.seekToMs(ms: Long) = seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 600))

private fun AVPlayer.status(stopped: Boolean) = RendererStatus(
    state = when {
        stopped -> TransportState.STOPPED
        timeControlStatus == AVPlayerTimeControlStatusPlaying -> TransportState.PLAYING
        timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> TransportState.TRANSITIONING
        else -> TransportState.PAUSED
    },
    positionMs = positionMs(),
    durationMs = durationMs(),
)

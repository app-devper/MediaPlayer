@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
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
                    if (stopped && player.positionMs() >= player.durationMs() - 500) player.seekToMs(0)
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
    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                showsPlaybackControls = true
                allowsPictureInPicturePlayback = !isAudio
                updatesNowPlayingInfoCenter = true
            }
        },
        modifier = modifier,
    )
}

private fun Double.toMs(): Long = if (isNaN() || isInfinite() || this < 0) 0 else (this * 1000).toLong()

private fun AVPlayer.positionMs(): Long = CMTimeGetSeconds(currentTime()).toMs()

private fun AVPlayer.durationMs(): Long = currentItem?.let { CMTimeGetSeconds(it.duration).toMs() } ?: 0

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

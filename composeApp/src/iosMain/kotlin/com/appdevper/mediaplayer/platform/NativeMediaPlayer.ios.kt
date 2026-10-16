@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.usesExternalPlaybackWhileExternalScreenIsActive
import platform.AVKit.AVPlayerViewController
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
) {
    val ended by rememberUpdatedState(onEnded)
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
        ) { _ -> ended() }
        player.play()
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }
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

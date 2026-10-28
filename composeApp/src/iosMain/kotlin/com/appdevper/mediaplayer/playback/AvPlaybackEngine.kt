@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.playback

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.session.EngineFacts
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.externalPlaybackActive
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.usesExternalPlaybackWhileExternalScreenIsActive
import platform.AVFoundation.volume
import platform.AVKit.AVPlayerViewController
import platform.AVKit.AVPlayerViewControllerDelegateProtocol
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
import platform.darwin.NSObject

/** AVPlayer as the app's [PlaybackEngine]. Main thread only. */
class AvPlaybackEngine : PlaybackEngine {

    val player: AVPlayer = AVPlayer().apply {
        // Video moves to an Apple TV when an AirPlay route is picked.
        allowsExternalPlayback = true
        usesExternalPlaybackWhileExternalScreenIsActive = true
    }

    private var url: NSURL? = null
    private var badUrl = false

    // Played to the end (the notification); AVKit's own controls can move it back without telling us,
    // so it only counts while the position is still at the end.
    private var ended = false

    /** AVKit's Picture in Picture is up; set through [pictureInPictureDelegate]. */
    private var pictureInPicture = false

    /** For the video view's AVPlayerViewController (its delegate is weak; this keeps it). */
    val pictureInPictureDelegate: AVPlayerViewControllerDelegateProtocol = object : NSObject(), AVPlayerViewControllerDelegateProtocol {
        override fun playerViewControllerWillStartPictureInPicture(playerViewController: AVPlayerViewController) {
            pictureInPicture = true
        }

        override fun playerViewControllerDidStopPictureInPicture(playerViewController: AVPlayerViewController) {
            pictureInPicture = false
        }
    }

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { note ->
            if (note?.`object` == player.currentItem && player.currentItem != null) ended = true
        }
    }

    override fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long) {
        this.url = toNsUrl(url)
        badUrl = this.url == null
        replaceItem(startPositionMs)
        if (playWhenReady) player.play() else player.pause()
    }

    override fun unload() {
        url = null
        badUrl = false
        ended = false
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) {
        ended = false
        player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 600))
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    /** A failed item never plays again: a new one, where it was. */
    override fun reload(positionMs: Long) = replaceItem(positionMs)

    private fun replaceItem(positionMs: Long) {
        ended = false
        player.replaceCurrentItemWithPlayerItem(url?.let { AVPlayerItem.playerItemWithURL(it) })
        if (positionMs > 0) player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 600))
    }

    override fun facts(): EngineFacts {
        val item = player.currentItem
        val positionMs = CMTimeGetSeconds(player.currentTime()).toMs()
        val durationMs = item?.let { CMTimeGetSeconds(it.duration).toMs() } ?: 0
        return EngineFacts(
            playing = player.timeControlStatus != AVPlayerTimeControlStatusPaused,
            loading = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            ended = ended && (durationMs <= 0 || positionMs >= durationMs - END_MARGIN_MS),
            positionMs = positionMs,
            durationMs = durationMs,
            error = if (badUrl) "This address can't be played" else item?.takeIf { it.status == AVPlayerItemStatusFailed }?.error?.localizedDescription,
            // In Picture in Picture or on an Apple TV it plays on with the app in the background.
            shownElsewhere = pictureInPicture || player.externalPlaybackActive,
        )
    }
}

private const val END_MARGIN_MS = 500L

private fun Double.toMs(): Long = if (isNaN() || isInfinite() || this < 0) 0 else (this * 1000).toLong()

private fun toNsUrl(url: String): NSURL? =
    NSURL.URLWithString(url) ?: NSString.create(string = url).stringByAddingPercentEncodingWithAllowedCharacters(
        NSCharacterSet.URLQueryAllowedCharacterSet,
    )?.let { NSURL.URLWithString(it) }

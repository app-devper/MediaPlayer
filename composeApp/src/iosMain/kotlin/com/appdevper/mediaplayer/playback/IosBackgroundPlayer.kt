@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.playback

import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
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
import platform.AVFoundation.externalPlaybackActive
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
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
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.URLQueryAllowedCharacterSet
import platform.Foundation.create
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.darwin.NSObject
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS's local player, one for the app (as Android's BackgroundPlayer): plays the session's current
 * song or video whether the player screen is showing or not, and with the app in the background
 * (UIBackgroundModes audio, the Playback audio session). It loads the current item, takes
 * [PlaybackSession.localCommands], reports the status and moves on at the end; the player screen
 * only shows it. The lock screen and headphones get now playing info and play/pause, next/previous
 * (our queue) and seeking. Videos pause while the app is in the background and go on when it's back.
 * Runs on the main thread.
 */
class IosBackgroundPlayer(private val session: PlaybackSession) {

    val player: AVPlayer = AVPlayer().apply {
        // Video moves to an Apple TV when an AirPlay route is picked.
        allowsExternalPlayback = true
        usesExternalPlaybackWhileExternalScreenIsActive = true
    }

    private val _stopped = MutableStateFlow(true)

    /** Loaded but not started, finished, or stopped by a controller: reported as STOPPED, not PAUSED. */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    /** The item in the player, or null (nothing, a photo, or playing on a renderer). */
    var loaded: MediaEntry? = null
        private set

    // Paused because the app went to the background (a video); it plays on when the app is back,
    // unless anything else paused, played or stopped it meanwhile.
    private var pausedInBackground = false
    private val scope = MainScope()

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

    // Playing when a call (or another app's audio) interrupted it: it goes on afterwards if iOS says so.
    private var playingBeforeInterruption = false

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { note ->
            if (note?.`object` == player.currentItem && loaded != null) {
                _stopped.value = true
                session.next()
            }
        }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            // Not while it's shown elsewhere: in Picture in Picture, or on an Apple TV (AirPlay).
            if (loaded?.kind == MediaKind.VIDEO && player.rate > 0f && !pictureInPicture && !player.externalPlaybackActive) {
                player.pause()
                pausedInBackground = true
            }
        }
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val info = note?.userInfo ?: return@addObserverForName
            val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) {
                playingBeforeInterruption = isPlaying
            } else if (type == AVAudioSessionInterruptionTypeEnded) {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
                if (playingBeforeInterruption && options and AVAudioSessionInterruptionOptionShouldResume != 0uL) resume()
                playingBeforeInterruption = false
            }
        }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            if (pausedInBackground) {
                pausedInBackground = false
                if (loaded?.kind == MediaKind.VIDEO) player.play()
            }
        }
        scope.launch {
            session.state
                .map { s -> Load(s.current.takeIf { s.target == PlaybackTarget.Local && it?.kind.playable }, s.autoPlay, s.startPositionMs, s.loads) }
                // A new item, the same one played again or moved here from a renderer; not every status update.
                .distinctUntilChanged { a, b -> a.loads == b.loads && a.entry?.id == b.entry?.id && a.entry?.streamUrl == b.entry?.streamUrl }
                .collect(::load)
        }
        scope.launch {
            session.state.map { if (it.localMuted) 0f else it.localVolume / 100f }.distinctUntilChanged().collect { player.volume = it }
        }
        scope.launch {
            session.localCommands.collect { cmd ->
                pausedInBackground = false
                when (cmd) {
                    LocalPlayerCommand.Play -> resume()
                    LocalPlayerCommand.Pause -> pause()
                    LocalPlayerCommand.Stop -> {
                        _stopped.value = true
                        player.pause()
                        seek(0, keepStopped = true)
                    }
                    is LocalPlayerCommand.Seek -> seek(cmd.positionMs, keepStopped = true)
                }
            }
        }
        scope.launch {
            while (true) {
                if (loaded != null) {
                    // Started from the lock screen or headphones also counts as playing.
                    if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) _stopped.value = false
                    session.reportLocalStatus(status())
                    updateNowPlaying()
                }
                delay(STATUS_INTERVAL_MS)
            }
        }
        setUpRemoteCommands()
    }

    private class Load(val entry: MediaEntry?, val autoPlay: Boolean, val startPositionMs: Long, val loads: Int)

    private fun load(load: Load) {
        val entry = load.entry
        val url = entry?.streamUrl?.let(::toNsUrl)
        pausedInBackground = false
        if (entry == null || url == null) {
            // As the on-screen player did when it left: the last report says stopped, not playing.
            if (loaded != null) session.reportLocalStatus(status().copy(state = TransportState.STOPPED))
            loaded = null
            _stopped.value = true
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            return
        }
        loaded = entry
        _stopped.value = !load.autoPlay
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem.playerItemWithURL(url))
        if (load.startPositionMs > 0) player.seekToTime(CMTimeMakeWithSeconds(load.startPositionMs / 1000.0, 600))
        if (load.autoPlay) player.play() else player.pause()
        updateNowPlaying()
    }

    /** Play, from our controls, a controller or the lock screen: from the start once finished. */
    fun resume() {
        val entry = loaded ?: return
        pausedInBackground = false
        // A failed item never plays again: load it anew, where it was.
        if (player.currentItem?.status == AVPlayerItemStatusFailed) {
            val at = positionMs()
            entry.streamUrl?.let(::toNsUrl)?.let { player.replaceCurrentItemWithPlayerItem(AVPlayerItem.playerItemWithURL(it)) }
            if (at > 0) player.seekToTime(CMTimeMakeWithSeconds(at / 1000.0, 600))
        }
        if (_stopped.value && atEnd()) player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
        _stopped.value = false
        player.play()
    }

    fun pause() {
        pausedInBackground = false
        player.pause()
    }

    fun seek(positionMs: Long, keepStopped: Boolean = false) {
        if (!keepStopped) _stopped.value = false
        player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 600))
    }

    /** Meant to be playing (playing, or waiting to), as opposed to paused or stopped. */
    val isPlaying: Boolean get() = !_stopped.value && player.timeControlStatus != AVPlayerTimeControlStatusPaused

    /** Why the current item can't play, if it failed. */
    val error: String? get() = player.currentItem?.takeIf { it.status == AVPlayerItemStatusFailed }?.error?.localizedDescription

    fun status() = RendererStatus(
        state = when {
            _stopped.value -> TransportState.STOPPED
            player.timeControlStatus == AVPlayerTimeControlStatusPlaying -> TransportState.PLAYING
            player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> TransportState.TRANSITIONING
            else -> TransportState.PAUSED
        },
        positionMs = positionMs(),
        durationMs = durationMs(),
    )

    private fun setUpRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun ok(block: () -> Unit) = run {
            if (loaded == null) return@run MPRemoteCommandHandlerStatusCommandFailed
            block()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.playCommand.addTargetWithHandler { _ -> ok(::resume) }
        center.pauseCommand.addTargetWithHandler { _ -> ok(::pause) }
        center.togglePlayPauseCommand.addTargetWithHandler { _ -> ok { if (isPlaying) pause() else resume() } }
        center.nextTrackCommand.addTargetWithHandler { _ -> ok { session.next() } }
        // Like other players: back to the start, or the previous item when near the start.
        center.previousTrackCommand.addTargetWithHandler { _ ->
            ok { if (positionMs() > RESTART_MS || !session.previous()) seek(0) }
        }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            ok { seek((at * 1000).toLong()) }
        }
    }

    private fun updateNowPlaying() {
        val entry = loaded ?: return
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = buildMap<Any?, Any?> {
            put(MPMediaItemPropertyTitle, entry.title)
            entry.artist?.let { put(MPMediaItemPropertyArtist, it) }
            entry.album?.let { put(MPMediaItemPropertyAlbumTitle, it) }
            durationMs().takeIf { it > 0 }?.let { put(MPMediaItemPropertyPlaybackDuration, it / 1000.0) }
            put(MPNowPlayingInfoPropertyElapsedPlaybackTime, positionMs() / 1000.0)
            put(MPNowPlayingInfoPropertyPlaybackRate, if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) 1.0 else 0.0)
        }
    }

    private fun positionMs(): Long = CMTimeGetSeconds(player.currentTime()).toMs()

    private fun durationMs(): Long = player.currentItem?.let { CMTimeGetSeconds(it.duration).toMs() } ?: 0

    /** Finished (a known duration, and at its end). A stream of unknown length never is. */
    private fun atEnd(): Boolean = durationMs() > 0 && positionMs() >= durationMs() - 500

    private val MediaKind?.playable get() = this == MediaKind.AUDIO || this == MediaKind.VIDEO

    private companion object {
        const val STATUS_INTERVAL_MS = 500L
        const val RESTART_MS = 3_000L
    }
}

private fun Double.toMs(): Long = if (isNaN() || isInfinite() || this < 0) 0 else (this * 1000).toLong()

private fun toNsUrl(url: String): NSURL? =
    NSURL.URLWithString(url) ?: NSString.create(string = url).stringByAddingPercentEncodingWithAllowedCharacters(
        NSCharacterSet.URLQueryAllowedCharacterSet,
    )?.let { NSURL.URLWithString(it) }

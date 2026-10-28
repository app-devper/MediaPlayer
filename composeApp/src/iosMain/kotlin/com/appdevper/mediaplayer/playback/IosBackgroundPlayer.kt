@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appdevper.mediaplayer.playback

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
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
 * Keeps iOS's local player ([AvPlaybackEngine], driven by [PlaybackSession.local]) going with the app
 * in the background (UIBackgroundModes audio, the Playback audio session): the lock screen and
 * headphones get now playing info and play/pause, next/previous (the session's) and seeking; the
 * player hears when the app goes to the background and comes back, and plays on after a phone call
 * when iOS says so. Runs on the main thread.
 */
class IosBackgroundPlayer(private val session: PlaybackSession) {

    private val local = session.local
    private val scope = MainScope()

    // Playing when a call (or another app's audio) interrupted it: it goes on afterwards if iOS says so.
    private var playingBeforeInterruption = false

    private val isPlaying: Boolean
        get() = local.status.value?.state.let { it == TransportState.PLAYING || it == TransportState.TRANSITIONING }

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            local.onAppBackground()
        }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            local.onAppForeground()
        }
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val info = note?.userInfo ?: return@addObserverForName
            val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) {
                playingBeforeInterruption = isPlaying
            } else if (type == AVAudioSessionInterruptionTypeEnded) {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
                if (playingBeforeInterruption && options and AVAudioSessionInterruptionOptionShouldResume != 0uL) local.play()
                playingBeforeInterruption = false
            }
        }
        scope.launch {
            combine(local.loaded, local.status, ::updateNowPlaying).collect {}
        }
        setUpRemoteCommands()
    }

    private fun setUpRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun ok(block: () -> Unit) = run {
            if (local.loaded.value == null) return@run MPRemoteCommandHandlerStatusCommandFailed
            block()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.playCommand.addTargetWithHandler { _ -> ok(local::play) }
        center.pauseCommand.addTargetWithHandler { _ -> ok(local::pause) }
        center.togglePlayPauseCommand.addTargetWithHandler { _ -> ok { if (isPlaying) local.pause() else local.play() } }
        center.nextTrackCommand.addTargetWithHandler { _ -> ok { session.next() } }
        center.previousTrackCommand.addTargetWithHandler { _ -> ok { session.previous() } }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            ok { local.seek((at * 1000).toLong()) }
        }
    }

    private fun updateNowPlaying(entry: MediaEntry?, status: RendererStatus?) {
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = entry?.let {
            buildMap<Any?, Any?> {
                put(MPMediaItemPropertyTitle, entry.title)
                entry.artist?.let { put(MPMediaItemPropertyArtist, it) }
                entry.album?.let { put(MPMediaItemPropertyAlbumTitle, it) }
                status?.durationMs?.takeIf { it > 0 }?.let { put(MPMediaItemPropertyPlaybackDuration, it / 1000.0) }
                put(MPNowPlayingInfoPropertyElapsedPlaybackTime, (status?.positionMs ?: 0) / 1000.0)
                put(MPNowPlayingInfoPropertyPlaybackRate, if (status?.state == TransportState.PLAYING) 1.0 else 0.0)
            }
        }
    }
}

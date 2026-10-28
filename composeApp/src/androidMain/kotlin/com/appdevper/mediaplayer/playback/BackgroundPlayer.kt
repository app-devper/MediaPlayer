package com.appdevper.mediaplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import com.google.common.util.concurrent.ListenableFuture

/**
 * Keeps Android's local player ([ExoPlaybackEngine], driven by [PlaybackSession.local]) going with
 * the app in the background: publishes it as a media session through [PlaybackService] (media
 * notification, lock-screen, headset and Bluetooth controls), and tells it when the app goes to the
 * background and comes back. Runs on the main thread.
 */
@OptIn(UnstableApi::class)
class BackgroundPlayer(
    private val context: Context,
    engine: ExoPlaybackEngine,
    private val session: PlaybackSession,
) {
    private val local = session.local

    /** What the media session drives: the session's commands, as our own controls send them. */
    val sessionPlayer: Player = object : ForwardingPlayer(engine.player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command)

        override fun play() = session.play()
        override fun pause() = session.pause()
        override fun seekTo(positionMs: Long) = session.seek(positionMs)
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = session.seek(positionMs)
        override fun seekToNext() {
            session.next()
        }
        override fun seekToNextMediaItem() {
            session.next()
        }
        override fun seekToPrevious() {
            session.previous()
        }
        override fun seekToPreviousMediaItem() {
            session.previous()
        }
    }

    private var controller: ListenableFuture<MediaController>? = null

    init {
        engine.player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) connectService() // again, if it was swiped away meanwhile
            }
        })
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = local.onAppBackground()
            override fun onStart(owner: LifecycleOwner) = local.onAppForeground()
        })
    }

    /**
     * Binding a controller creates [PlaybackService], which publishes the media session; Media3 then
     * keeps it in the foreground (with its notification) while something plays.
     */
    private fun connectService() {
        if (controller != null) return
        controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }

    /** The service stopped (e.g. swiped away while paused): the next item starts it again. */
    fun onServiceGone() {
        controller?.let(MediaController::releaseFuture)
        controller = null
    }
}

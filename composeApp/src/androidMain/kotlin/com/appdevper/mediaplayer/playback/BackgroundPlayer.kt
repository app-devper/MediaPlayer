package com.appdevper.mediaplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android's local player, one for the app: plays the session's current song or video whether the
 * player screen is showing or not, and with the app in the background (in [PlaybackService], with a
 * media notification, lock-screen and headset controls). It does what the on-screen player did on
 * the other platforms: loads the current item, takes [PlaybackSession.localCommands], reports the
 * status and moves on at the end. The player screen only shows it ([com.appdevper.mediaplayer.platform.NativeMediaPlayer]).
 *
 * Videos pause while the app is in the background and go on when it's back; songs play on.
 * Everything here runs on the main thread, as ExoPlayer requires.
 */
@OptIn(UnstableApi::class)
class BackgroundPlayer(private val context: Context, private val session: PlaybackSession) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        // Pauses for calls and other apps' audio, and when headphones are unplugged.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    /** What the media session (notification, lock screen, headset) drives: next/previous go to the queue. */
    val sessionPlayer: Player = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command)

        override fun play() = resume()
        override fun seekToNext() { session.next() }
        override fun seekToNextMediaItem() { session.next() }
        override fun seekToPreviousMediaItem() { session.previous() }

        // Like other players: back to the start, or the previous item when near the start.
        override fun seekToPrevious() {
            if (player.currentPosition > RESTART_MS || !session.previous()) player.seekTo(0)
        }
    }

    private val _stopped = MutableStateFlow(true)

    /** Loaded but not started, finished, or stopped by a controller: reported as STOPPED, not PAUSED. */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The item in the player, or null (nothing, a photo, or playing on a renderer). */
    private var loaded: MediaEntry? = null
    private var pausedInBackground = false
    private var controller: ListenableFuture<MediaController>? = null
    private val scope = MainScope()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && loaded != null) {
                    _stopped.value = true
                    session.next()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) _stopped.value = false // started from the notification or a headset too
            }

            override fun onPlayerError(e: PlaybackException) {
                _error.value = e.message ?: e.errorCodeName
            }
        })
        scope.launch {
            session.state
                .map { s -> Load(s.current.takeIf { s.target == PlaybackTarget.Local && it?.kind.playable }, s.autoPlay, s.startPositionMs) }
                // A new item, or the same one moved here from a renderer; not every status update.
                .distinctUntilChanged { a, b -> a.entry?.id == b.entry?.id && a.entry?.streamUrl == b.entry?.streamUrl }
                .collect(::load)
        }
        scope.launch {
            session.state.map { if (it.localMuted) 0f else it.localVolume / 100f }.distinctUntilChanged().collect { player.volume = it }
        }
        scope.launch {
            session.localCommands.collect { cmd ->
                when (cmd) {
                    LocalPlayerCommand.Play -> resume()
                    LocalPlayerCommand.Pause -> player.pause()
                    LocalPlayerCommand.Stop -> {
                        _stopped.value = true
                        player.pause()
                        player.seekTo(0)
                    }
                    is LocalPlayerCommand.Seek -> player.seekTo(cmd.positionMs)
                }
            }
        }
        scope.launch {
            while (true) {
                if (loaded != null) session.reportLocalStatus(status())
                delay(STATUS_INTERVAL_MS)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (loaded?.kind == MediaKind.VIDEO && player.playWhenReady) {
                    pausedInBackground = true
                    player.pause()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                if (pausedInBackground) {
                    pausedInBackground = false
                    if (loaded?.kind == MediaKind.VIDEO) player.play()
                }
            }
        })
    }

    private class Load(val entry: MediaEntry?, val autoPlay: Boolean, val startPositionMs: Long)

    private fun load(load: Load) {
        val entry = load.entry
        val url = entry?.streamUrl
        pausedInBackground = false
        _error.value = null
        if (entry == null || url == null) {
            loaded = null
            _stopped.value = true
            player.stop()
            player.clearMediaItems()
            return
        }
        loaded = entry
        _stopped.value = !load.autoPlay
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(entry.artist)
                        .setAlbumTitle(entry.album)
                        .setArtworkUri(entry.thumbnailUrl?.let(Uri::parse))
                        .build(),
                )
                .build(),
            load.startPositionMs.coerceAtLeast(0),
        )
        player.prepare()
        player.playWhenReady = load.autoPlay
        connectService()
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

    /** Play, from our controls, a controller or the notification: from the start once ended; after an error, loaded again. */
    fun resume() {
        if (loaded == null) return
        _stopped.value = false
        if (_error.value != null) {
            _error.value = null
            player.prepare()
        }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun seek(positionMs: Long) {
        _stopped.value = false
        player.seekTo(positionMs)
    }

    fun status() = RendererStatus(
        state = when {
            _stopped.value || player.playbackState == Player.STATE_ENDED -> TransportState.STOPPED
            player.isPlaying -> TransportState.PLAYING
            player.playWhenReady && player.playbackState == Player.STATE_BUFFERING -> TransportState.TRANSITIONING
            else -> TransportState.PAUSED
        },
        positionMs = player.currentPosition.coerceAtLeast(0),
        durationMs = player.duration.takeIf { it > 0 } ?: 0,
        ended = player.playbackState == Player.STATE_ENDED,
    )

    private val MediaKind?.playable get() = this == MediaKind.AUDIO || this == MediaKind.VIDEO

    private companion object {
        const val STATUS_INTERVAL_MS = 500L
        const val RESTART_MS = 3_000L
    }
}

package com.appdevper.mediaplayer.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.session.EngineFacts
import com.appdevper.mediaplayer.domain.session.PlaybackEngine

/** ExoPlayer as the app's [PlaybackEngine]. Main thread only, as ExoPlayer requires. */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(context: Context) : PlaybackEngine {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        // Pauses for calls and other apps' audio, and when headphones are unplugged.
        .setAudioAttributes(audioAttributes(MediaKind.AUDIO), true)
        .setHandleAudioBecomingNoisy(true)
        // Keeps Wi-Fi awake for NAS / DLNA streams with the screen off.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    override fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long) {
        player.setAudioAttributes(audioAttributes(entry.kind), true)
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
            startPositionMs,
        )
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun unload() {
        player.stop()
        player.clearMediaItems()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun reload(positionMs: Long) {
        player.seekTo(positionMs)
        player.prepare()
    }

    override fun facts() = EngineFacts(
        // Not while a call holds the audio focus, nor stopped by a controller straight on ExoPlayer.
        playing = player.playWhenReady && player.playbackState != Player.STATE_IDLE &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        loading = player.playbackState == Player.STATE_BUFFERING,
        ended = player.playbackState == Player.STATE_ENDED,
        positionMs = player.currentPosition,
        durationMs = player.duration.takeIf { it > 0 } ?: 0,
        error = player.playerError?.let { it.message ?: it.errorCodeName },
    )

    private fun audioAttributes(kind: MediaKind) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (kind == MediaKind.VIDEO) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
}

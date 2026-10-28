package com.appdevper.mediaplayer.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.TextTrack
import com.appdevper.mediaplayer.domain.session.EngineFacts
import com.appdevper.mediaplayer.domain.subtitle.SubtitleFiles
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

    // The selected subtitle track's lines on screen now (we draw them, not PlayerView).
    private var cueText: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cueText = cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n").ifEmpty { null }
            }
        })
    }

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
        selectTextTrack(null) // subtitles off until picked
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

    /** Text tracks only: image ones (PGS, VobSub, DVB) have no text for us to draw. Ids are "group:track". */
    private fun textTracks(): List<TextTrack> = buildList {
        player.currentTracks.groups.forEachIndexed { g, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (t in 0 until group.length) {
                val format = group.getTrackFormat(t)
                if (!group.isTrackSupported(t) || listOfNotNull(format.sampleMimeType, format.codecs).any { it in imageSubtitles }) continue
                val language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }
                add(TextTrack("$g:$t", format.label ?: SubtitleFiles.label(language, "Track ${size + 1}"), language))
            }
        }
    }

    override fun selectTextTrack(id: String?) {
        val (g, t) = id?.split(':')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 } ?: listOf(-1, -1)
        val group = player.currentTracks.groups.getOrNull(g)?.takeIf { it.type == C.TRACK_TYPE_TEXT && t in 0 until it.length }
        cueText = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, group == null)
            .apply { if (group != null) setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t)) }
            .build()
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
        textTracks = textTracks(),
        subtitleText = cueText,
        selectedTextTrack = selectedTextTrack(),
    )

    /** The track asked for (parameters apply a moment later, so not what currentTracks says yet). */
    private fun selectedTextTrack(): String? {
        val params = player.trackSelectionParameters
        if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) return null
        player.currentTracks.groups.forEachIndexed { g, group ->
            val override = params.overrides[group.mediaTrackGroup] ?: return@forEachIndexed
            if (group.type == C.TRACK_TYPE_TEXT) override.trackIndices.firstOrNull()?.let { return "$g:$it" }
        }
        return null
    }

    private fun audioAttributes(kind: MediaKind) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (kind == MediaKind.VIDEO) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private companion object {
        val imageSubtitles = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)
    }
}

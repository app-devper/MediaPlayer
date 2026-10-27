package com.appdevper.mediaplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.appdevper.mediaplayer.MainActivity
import org.koin.android.ext.android.inject

/**
 * Publishes [BackgroundPlayer] as a media session: Media3 shows the playback notification, keeps the
 * service in the foreground while something plays, and routes lock-screen, headset and Bluetooth
 * controls to it. The player itself belongs to [BackgroundPlayer], not to this service.
 */
class PlaybackService : MediaSessionService() {

    private val background: BackgroundPlayer by inject()
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, background.sessionPlayer)
            .setSessionActivity(open)
            .setCallback(TransportOnly)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // Swiped away from recents: keep going only while something is playing. Our own controller keeps
    // the service bound, so it lets go first, or the paused notification would stay.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            background.onServiceGone()
            pauseAllPlayersAndStopSelf()
        }
    }

    /**
     * Other apps, the system and Bluetooth may control playback (play, pause, seek, skip), but not put
     * their own media into our player: the service is exported, and the player would open whatever URI
     * it's given (file://, content://) with this app's access. We never load through the session either.
     */
    private object TransportOnly : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_PLAYLIST_METADATA)
                        .build(),
                )
                .build()
    }

    override fun onDestroy() {
        background.onServiceGone()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

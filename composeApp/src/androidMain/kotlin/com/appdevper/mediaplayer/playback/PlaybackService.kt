package com.appdevper.mediaplayer.playback

import android.app.PendingIntent
import android.content.Intent
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
        mediaSession = MediaSession.Builder(this, background.sessionPlayer).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // Swiped away from recents: keep going only while something is playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        background.onServiceGone()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

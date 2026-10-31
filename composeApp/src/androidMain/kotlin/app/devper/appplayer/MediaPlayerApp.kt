package app.devper.appplayer

import android.app.Application
import app.devper.appplayer.di.initKoin
import app.devper.appplayer.playback.BackgroundPlayer
import org.koin.android.ext.koin.androidContext
import org.koin.java.KoinJavaComponent.get

class MediaPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin { androidContext(this@MediaPlayerApp) }
        // The media session and the app lifecycle for the local player, with or without the player screen.
        get<BackgroundPlayer>(BackgroundPlayer::class.java)
    }
}

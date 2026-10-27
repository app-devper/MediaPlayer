package com.appdevper.mediaplayer

import android.app.Application
import com.appdevper.mediaplayer.di.initKoin
import com.appdevper.mediaplayer.playback.BackgroundPlayer
import org.koin.android.ext.koin.androidContext
import org.koin.java.KoinJavaComponent.get

class MediaPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin { androidContext(this@MediaPlayerApp) }
        // Plays the session's songs and videos from now on, with or without the player screen.
        get<BackgroundPlayer>(BackgroundPlayer::class.java)
    }
}

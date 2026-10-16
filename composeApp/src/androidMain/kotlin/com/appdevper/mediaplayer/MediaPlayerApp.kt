package com.appdevper.mediaplayer

import android.app.Application
import com.appdevper.mediaplayer.di.initKoin
import org.koin.android.ext.koin.androidContext

class MediaPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin { androidContext(this@MediaPlayerApp) }
    }
}

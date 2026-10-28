package com.appdevper.mediaplayer.di

import com.appdevper.mediaplayer.data.platform.ServerHost
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import com.appdevper.mediaplayer.playback.AvPlaybackEngine
import com.appdevper.mediaplayer.playback.IosBackgroundPlayer
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** iOS suspends sockets in the background, so keep the screen awake while sharing. */
private class IdleTimerServerHost : ServerHost {
    override fun onServerStarted(name: String) {
        dispatch_async(dispatch_get_main_queue()) { UIApplication.sharedApplication.idleTimerDisabled = true }
    }

    override fun onServerStopped() {
        dispatch_async(dispatch_get_main_queue()) { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

actual val platformAppModule: Module = module {
    single<ServerHost> { IdleTimerServerHost() }
    single { AvPlaybackEngine() } bind PlaybackEngine::class
    single { IosBackgroundPlayer(get()) }
}

package com.appdevper.mediaplayer.di

import com.appdevper.mediaplayer.data.platform.ServerHost
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import com.appdevper.mediaplayer.playback.FfmpegPlaybackEngine
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<ServerHost> { ServerHost.None }
    single { FfmpegPlaybackEngine() } bind PlaybackEngine::class
}

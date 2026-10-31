package app.devper.appplayer.di

import app.devper.appplayer.data.platform.ServerHost
import app.devper.appplayer.domain.session.PlaybackEngine
import app.devper.appplayer.playback.FfmpegPlaybackEngine
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<ServerHost> { ServerHost.None }
    single { FfmpegPlaybackEngine() } bind PlaybackEngine::class
}

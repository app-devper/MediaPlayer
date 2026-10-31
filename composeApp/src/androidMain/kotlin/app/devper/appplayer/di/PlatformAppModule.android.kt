package app.devper.appplayer.di

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.devper.appplayer.data.platform.ServerHost
import app.devper.appplayer.domain.session.PlaybackEngine
import app.devper.appplayer.playback.BackgroundPlayer
import app.devper.appplayer.playback.ExoPlaybackEngine
import app.devper.appplayer.service.ServerService
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Runs the DLNA server inside a foreground service so Android keeps it alive. */
private class ServiceServerHost(private val context: Context) : ServerHost {
    override fun onServerStarted(name: String) {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java).putExtra(ServerService.EXTRA_NAME, name))
        }
    }

    override fun onServerStopped() {
        context.stopService(Intent(context, ServerService::class.java))
    }
}

actual val platformAppModule: Module = module {
    single<ServerHost> { ServiceServerHost(androidContext()) }
    single { ExoPlaybackEngine(androidContext()) } bind PlaybackEngine::class
    single { BackgroundPlayer(androidContext(), get(), get()) }
}

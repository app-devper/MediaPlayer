package com.appdevper.mediaplayer.di

import com.appdevper.mediaplayer.data.platform.ServerHost
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<ServerHost> { ServerHost.None }
}

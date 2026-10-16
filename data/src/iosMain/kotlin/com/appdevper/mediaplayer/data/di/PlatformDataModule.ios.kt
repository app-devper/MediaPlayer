package com.appdevper.mediaplayer.data.di

import com.appdevper.mediaplayer.data.platform.DocumentsLibraryFactory
import com.appdevper.mediaplayer.data.platform.MediaLibraryFactory
import com.appdevper.mediaplayer.data.platform.MulticastLock
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformDataModule: Module = module {
    single<MulticastLock> { MulticastLock.None }
    single<MediaLibraryFactory> { DocumentsLibraryFactory() }
}

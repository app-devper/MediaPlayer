package com.appdevper.mediaplayer.data.di

import com.appdevper.mediaplayer.data.platform.AndroidMulticastLock
import com.appdevper.mediaplayer.data.platform.MediaLibraryFactory
import com.appdevper.mediaplayer.data.platform.MediaStoreLibraryFactory
import com.appdevper.mediaplayer.data.platform.MulticastLock
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformDataModule: Module = module {
    single<MulticastLock> { AndroidMulticastLock(androidContext()) }
    single<MediaLibraryFactory> { MediaStoreLibraryFactory(androidContext()) }
}

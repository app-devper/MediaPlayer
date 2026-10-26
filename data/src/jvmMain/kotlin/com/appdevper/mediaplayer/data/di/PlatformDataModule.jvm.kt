package com.appdevper.mediaplayer.data.di

import com.appdevper.mediaplayer.data.platform.AppStorage
import com.appdevper.mediaplayer.data.platform.FolderLibraryFactory
import com.appdevper.mediaplayer.data.platform.MediaLibraryFactory
import com.appdevper.mediaplayer.data.platform.MulticastLock
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformDataModule: Module = module {
    single<MulticastLock> { MulticastLock.None }
    single<MediaLibraryFactory> { FolderLibraryFactory() }
    single<MulticastLock>(AirPlayMulticast) { MulticastLock.None }
    single<AppStorage> {
        object : AppStorage {
            override val dir: String = java.io.File(System.getProperty("user.home"), ".mediaplayer").apply { mkdirs() }.absolutePath
        }
    }
}

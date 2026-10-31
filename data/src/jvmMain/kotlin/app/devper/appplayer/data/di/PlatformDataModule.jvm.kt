package app.devper.appplayer.data.di

import app.devper.appplayer.data.platform.AppStorage
import app.devper.appplayer.data.platform.FolderLibraryFactory
import app.devper.appplayer.data.platform.MediaLibraryFactory
import app.devper.appplayer.data.platform.MulticastLock
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

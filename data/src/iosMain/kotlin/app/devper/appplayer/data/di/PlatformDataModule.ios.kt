package app.devper.appplayer.data.di

import app.devper.appplayer.data.platform.AppStorage
import app.devper.appplayer.data.platform.DocumentsLibraryFactory
import app.devper.appplayer.data.platform.MediaLibraryFactory
import app.devper.appplayer.data.platform.MulticastLock
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformDataModule: Module = module {
    single<MulticastLock> { MulticastLock.None }
    single<MediaLibraryFactory> { DocumentsLibraryFactory() }
    single<MulticastLock>(AirPlayMulticast) { MulticastLock.None }
    // No AirPlay receiving on iOS; nothing is stored.
    single<AppStorage> { object : AppStorage { override val dir: String = "" } }
}

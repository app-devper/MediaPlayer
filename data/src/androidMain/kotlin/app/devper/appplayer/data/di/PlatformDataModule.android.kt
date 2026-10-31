package app.devper.appplayer.data.di

import app.devper.appplayer.data.platform.AndroidMulticastLock
import app.devper.appplayer.data.platform.AppStorage
import app.devper.appplayer.data.platform.MediaLibraryFactory
import app.devper.appplayer.data.platform.MediaStoreLibraryFactory
import app.devper.appplayer.data.platform.MulticastLock
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformDataModule: Module = module {
    single<MulticastLock> { AndroidMulticastLock(androidContext()) }
    single<MediaLibraryFactory> { MediaStoreLibraryFactory(androidContext()) }
    single<MulticastLock>(AirPlayMulticast) { AndroidMulticastLock(androidContext(), "MediaPlayer-AirPlay") }
    single<AppStorage> { object : AppStorage { override val dir: String = androidContext().filesDir.absolutePath } }
}

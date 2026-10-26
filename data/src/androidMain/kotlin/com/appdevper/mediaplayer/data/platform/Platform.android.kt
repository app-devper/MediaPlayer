package com.appdevper.mediaplayer.data.platform

import android.content.Context
import android.net.wifi.WifiManager
import com.appdevper.mediaplayer.domain.model.ServerCapabilities

actual val platformName: String = "Android"

actual fun defaultSharedFolders(): List<String> = emptyList()

/** Each instance is its own WifiManager lock, so separate users (SSDP, AirPlay) don't release each other's. */
class AndroidMulticastLock(private val context: Context, private val tag: String = "MediaPlayer-SSDP") : MulticastLock {
    private var lock: WifiManager.MulticastLock? = null

    override fun acquire() {
        if (lock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lock = wifi.createMulticastLock(tag).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun release() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }
}

class MediaStoreLibraryFactory(private val context: Context) : MediaLibraryFactory {
    override val capabilities = ServerCapabilities(canChooseFolders = false, sourceDescription = "videos, music and photos on this phone")
    override fun create(folders: List<String>): MediaLibrary = MediaStoreLibrary(context)
}

package com.appdevper.mediaplayer.data.platform

import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import kotlinx.io.RawSource

/** Short platform name, used for the default server name / SERVER header. */
expect val platformName: String

/** First non-loopback IPv4 address of the LAN interface (Wi‑Fi/Ethernet), or null when offline. */
expect fun localIpv4Address(): String?

class Datagram(val data: ByteArray, val host: String, val port: Int)

/** UDP socket abstraction used by SSDP. */
interface UdpSocket {
    /** Waits up to ~1s for a datagram; returns null on timeout. Throws once closed. */
    suspend fun receive(): Datagram?
    suspend fun send(data: ByteArray, host: String, port: Int)
    fun close()
}

/**
 * Opens a UDP socket bound to [port] (0 = ephemeral). When [multicastGroup] is set the socket
 * joins that group on the interface owning [localIp] and is bound with address reuse so it can
 * share port 1900 with other SSDP stacks on the machine.
 */
expect fun openUdpSocket(port: Int, multicastGroup: String?, localIp: String?): UdpSocket

/** Android needs a WifiManager.MulticastLock to receive multicast. */
interface MulticastLock {
    fun acquire()
    fun release()

    object None : MulticastLock {
        override fun acquire() {}
        override fun release() {}
    }
}

/** Lets the OS know the media server is running (Android: foreground service). Provided by the app. */
interface ServerHost {
    fun onServerStarted(name: String)
    fun onServerStopped()

    object None : ServerHost {
        override fun onServerStarted(name: String) {}
        override fun onServerStopped() {}
    }
}

/** A media file on this device that the built-in media server can share. */
data class LocalMedia(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val artist: String?,
    val album: String?,
    val mimeType: String,
    val size: Long,
    val durationMs: Long?,
    val resolution: String?,
    /** File path or content URI, interpreted by the owning [MediaLibrary]. */
    val source: String,
) {
    val extension: String get() = MimeTypes.extensionFor(mimeType, source)
}

interface MediaLibrary {
    suspend fun scan(kinds: Set<MediaKind>): List<LocalMedia>

    /** Opens the media for reading, positioned at [offset]. */
    fun open(media: LocalMedia, offset: Long): RawSource
}

/** Creates the platform's media library (MediaStore / folders / iOS Documents). */
interface MediaLibraryFactory {
    val capabilities: ServerCapabilities
    fun create(folders: List<String>): MediaLibrary
}

expect fun defaultSharedFolders(): List<String>

/** Private app storage (e.g. the AirPlay identity key). */
interface AppStorage {
    /** Absolute path of an existing directory. */
    val dir: String
}

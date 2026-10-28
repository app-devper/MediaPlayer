package com.appdevper.mediaplayer

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.repository.DeviceRepository
import com.appdevper.mediaplayer.domain.repository.LibraryRepository
import com.appdevper.mediaplayer.domain.repository.MediaServerRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

val fakeNas = MediaDevice("uuid:nas", "Living Room NAS", "Synology", "DS220+", "192.168.1.20", null, isServer = true, isRenderer = false)
val fakeTv = MediaDevice("uuid:tv", "Samsung Q80 TV", "Samsung", "QE55", "192.168.1.31", null, isServer = false, isRenderer = true)

class FakeDevices : DeviceRepository {
    override val devices = MutableStateFlow(listOf(fakeNas, fakeTv))
    override val isSearching = MutableStateFlow(false)
    override fun start() {}
    override fun refresh() {}
    override fun reset() {}
    override fun device(id: String) = devices.value.firstOrNull { it.id == id }

    /** Servers only found by asking their address (multicast doesn't reach them). */
    val byAddress = mutableMapOf<String, MediaDevice>()
    val asked = mutableListOf<String>()
    var slow = false
    override suspend fun addByAddress(address: String): List<MediaDevice> {
        asked += address
        if (slow) kotlinx.coroutines.delay(10_000)
        val found = listOfNotNull(byAddress[address.trim()])
        devices.value = (devices.value + found).distinct()
        if (found.isNotEmpty()) savedAddresses.value += address.trim()
        return found
    }

    override val savedAddresses = MutableStateFlow(emptyList<String>())
    override suspend fun forgetAddress(address: String) {
        // As the real one: trimmed, whatever the case.
        val text = address.trim()
        savedAddresses.value = savedAddresses.value.filterNot { it.equals(text, ignoreCase = true) }
        val host = byAddress.entries.firstOrNull { it.key.equals(text, ignoreCase = true) }?.value?.host
        devices.value = devices.value.filterNot { it.host == host }
    }
}

class FakeLibrary(var fail: Boolean = false) : LibraryRepository {
    override suspend fun browse(deviceId: String, folderId: String): List<MediaEntry> {
        if (fail) error("Server timed out")
        return listOf(
            MediaEntry("v1", folderId, "Weekend in Chiang Mai", MediaKind.VIDEO, streamUrl = "http://h/v1.mp4", durationMs = 754_000),
            MediaEntry("d1", folderId, "Movies", MediaKind.FOLDER, childCount = 42),
            MediaEntry("a1", folderId, "Khon Kaen Blues", MediaKind.AUDIO, artist = "Bodyslam", album = "Kraam", streamUrl = "http://h/a1.mp3", durationMs = 245_000),
            MediaEntry("d2", folderId, "Music", MediaKind.FOLDER, childCount = 318),
        )
    }
}

class FakeSettings : SettingsRepository {
    override val serverSettings = MutableStateFlow(ServerSettings("Worawit's MacBook", true, true, true, false, listOf("/Users/me/Movies", "/Users/me/Music")))
    override fun update(transform: (ServerSettings) -> ServerSettings) = serverSettings.update(transform)
}

class FakeServer : MediaServerRepository {
    val starts = mutableListOf<ServerSettings>()
    override val status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    override val capabilities = ServerCapabilities(canChooseFolders = true, sourceDescription = "the folders listed below")
    override suspend fun start(settings: ServerSettings) {
        starts += settings
        status.value = ServerStatus.Running(settings.name, "http://192.168.1.160:8191", mapOf(MediaKind.VIDEO to 12, MediaKind.AUDIO to 340))
    }
    override suspend fun stop() { status.value = ServerStatus.Stopped }
}


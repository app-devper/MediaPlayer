package com.appdevper.mediaplayer.domain.usecase

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
import kotlinx.coroutines.flow.StateFlow

class ObserveDevicesUseCase(private val repo: DeviceRepository) {
    operator fun invoke(): StateFlow<List<MediaDevice>> = repo.devices
    val isSearching: StateFlow<Boolean> get() = repo.isSearching
}

class RefreshDevicesUseCase(private val repo: DeviceRepository) {
    /** [clear] forgets known devices first (pull-to-refresh / network change). */
    operator fun invoke(clear: Boolean = false) = if (clear) repo.reset() else repo.refresh()
}

class GetDeviceUseCase(private val repo: DeviceRepository) {
    operator fun invoke(id: String): MediaDevice? = repo.device(id)
}

/** Lists a folder: sub-folders first, then media, both alphabetically when the server doesn't sort. */
class BrowseFolderUseCase(private val repo: LibraryRepository) {
    suspend operator fun invoke(deviceId: String, folderId: String): Result<List<MediaEntry>> =
        runCatching { repo.browse(deviceId, folderId) }
            .map { entries -> entries.sortedWith(compareBy<MediaEntry> { !it.isFolder }) }
}

/** Filters a folder listing by a free-text query (title, artist, album). */
class FilterEntriesUseCase {
    operator fun invoke(entries: List<MediaEntry>, query: String): List<MediaEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { e ->
            listOfNotNull(e.title, e.artist, e.album).any { it.contains(q, ignoreCase = true) }
        }
    }
}

class ObserveServerStatusUseCase(private val repo: MediaServerRepository) {
    operator fun invoke(): StateFlow<ServerStatus> = repo.status
}

class StartMediaServerUseCase(
    private val server: MediaServerRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke() = server.start(settings.serverSettings.value)
}

class StopMediaServerUseCase(private val server: MediaServerRepository) {
    suspend operator fun invoke() = server.stop()
}

class ObserveServerSettingsUseCase(private val repo: SettingsRepository) {
    operator fun invoke(): StateFlow<ServerSettings> = repo.serverSettings
}

/**
 * Saves settings. Returns true when the change affects what a running server publishes,
 * so the caller can restart it.
 */
class UpdateServerSettingsUseCase(private val repo: SettingsRepository) {
    operator fun invoke(transform: (ServerSettings) -> ServerSettings): Boolean {
        val before = repo.serverSettings.value
        repo.update(transform)
        val after = repo.serverSettings.value
        return before.name != after.name || before.sharedKinds != after.sharedKinds || before.folders != after.folders
    }
}

/** Counts per kind for the server summary line. */
fun Map<MediaKind, Int>.countOf(kind: MediaKind): Int = this[kind] ?: 0

class GetServerCapabilitiesUseCase(private val repo: MediaServerRepository) {
    operator fun invoke(): ServerCapabilities = repo.capabilities
}

/** App launch: start network discovery and, if enabled, the built-in server. */
class AppStartupUseCase(
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val server: MediaServerRepository,
) {
    suspend operator fun invoke() {
        devices.start()
        if (settings.serverSettings.value.autoStart) server.start(settings.serverSettings.value)
    }
}

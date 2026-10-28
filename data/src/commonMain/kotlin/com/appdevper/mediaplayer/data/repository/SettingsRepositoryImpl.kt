package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.platform.defaultSharedFolders
import com.appdevper.mediaplayer.data.platform.platformName
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** Persisted preferences (replaces the old `ServerSettings` / SharedPreferences). */
class SettingsRepositoryImpl(private val s: Settings = Settings()) : SettingsRepository {

    private val defaultName = "MediaPlayer ($platformName)"

    private val _serverSettings = MutableStateFlow(load())
    override val serverSettings: StateFlow<ServerSettings> = _serverSettings.asStateFlow()

    /** Stable device UDN so renderers/TVs keep recognising this server across restarts. */
    val serverUdn: String
        get() = s.getStringOrNull(KEY_UDN) ?: "uuid:${MediaServer.randomUuid()}".also { s.putString(KEY_UDN, it) }

    /** Separate stable UDN for the renderer: controllers cache devices by UDN. */
    val rendererUdn: String
        get() = s.getStringOrNull(KEY_RENDERER_UDN) ?: "uuid:${MediaServer.randomUuid()}".also { s.putString(KEY_RENDERER_UDN, it) }

    /** Stable AirPlay device id (6 bytes, locally administered MAC-like): senders remember receivers by it. */
    val airPlayDeviceId: ByteArray
        get() = s.getStringOrNull(KEY_AIRPLAY_ID)?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            ?: Random.nextBytes(6).also { it[0] = ((it[0].toInt() and 0xfe) or 0x02).toByte() }
                .also { id -> s.putString(KEY_AIRPLAY_ID, id.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }) }

    /** Public keys of AirPlay senders that paired with the PIN (they skip it next time). */
    val airPlayPairedKeys: Set<String>
        get() = s.getStringOrNull(KEY_AIRPLAY_PAIRED)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    fun addAirPlayPairedKey(publicKey: String) {
        s.putString(KEY_AIRPLAY_PAIRED, (airPlayPairedKeys + publicKey).joinToString("\n"))
    }

    /** Media server addresses the user added by hand (asked directly, without multicast discovery). */
    val serverAddresses: List<String>
        get() = s.getStringOrNull(KEY_SERVER_ADDRESSES)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun addServerAddress(address: String) {
        if (serverAddresses.none { it.equals(address, ignoreCase = true) }) s.putString(KEY_SERVER_ADDRESSES, (serverAddresses + address).joinToString("\n"))
    }

    override fun update(transform: (ServerSettings) -> ServerSettings) {
        val next = transform(_serverSettings.value).let { it.copy(name = it.name.trim().ifEmpty { defaultName }) }
        s.putString(KEY_NAME, next.name)
        s.putBoolean(KEY_AUTO_RUN, next.autoStart)
        s.putBoolean(KEY_VIDEO, next.shareVideo)
        s.putBoolean(KEY_AUDIO, next.shareAudio)
        s.putBoolean(KEY_IMAGE, next.shareImage)
        s.putString(KEY_FOLDERS, next.folders.joinToString("\n"))
        s.putBoolean(KEY_RECEIVER, next.receiverEnabled)
        s.putBoolean(KEY_AIRPLAY, next.airPlayEnabled)
        s.putBoolean(KEY_AIRPLAY_PIN, next.airPlayRequirePin)
        _serverSettings.value = next
    }

    private fun load() = ServerSettings(
        name = s.getString(KEY_NAME, defaultName),
        autoStart = s.getBoolean(KEY_AUTO_RUN, false),
        shareVideo = s.getBoolean(KEY_VIDEO, true),
        shareAudio = s.getBoolean(KEY_AUDIO, true),
        shareImage = s.getBoolean(KEY_IMAGE, true),
        folders = s.getStringOrNull(KEY_FOLDERS)?.split('\n')?.filter { it.isNotBlank() } ?: defaultSharedFolders(),
        receiverEnabled = s.getBoolean(KEY_RECEIVER, false),
        airPlayEnabled = s.getBoolean(KEY_AIRPLAY, false),
        airPlayRequirePin = s.getBoolean(KEY_AIRPLAY_PIN, true),
    )

    private companion object {
        const val KEY_NAME = "server_name"
        const val KEY_AUTO_RUN = "auto_run"
        const val KEY_VIDEO = "allow_video"
        const val KEY_AUDIO = "allow_audio"
        const val KEY_IMAGE = "allow_image"
        const val KEY_FOLDERS = "shared_folders"
        const val KEY_UDN = "server_udn"
        const val KEY_RENDERER_UDN = "renderer_udn"
        const val KEY_RECEIVER = "receiver_enabled"
        const val KEY_AIRPLAY = "airplay_enabled"
        const val KEY_SERVER_ADDRESSES = "server_addresses"
        const val KEY_AIRPLAY_PIN = "airplay_require_pin"
        const val KEY_AIRPLAY_ID = "airplay_device_id"
        const val KEY_AIRPLAY_PAIRED = "airplay_paired_keys"
    }
}

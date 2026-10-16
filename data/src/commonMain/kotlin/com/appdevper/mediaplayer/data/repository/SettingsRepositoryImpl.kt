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

/** Persisted preferences (replaces the old `ServerSettings` / SharedPreferences). */
class SettingsRepositoryImpl(private val s: Settings = Settings()) : SettingsRepository {

    private val defaultName = "MediaPlayer ($platformName)"

    private val _serverSettings = MutableStateFlow(load())
    override val serverSettings: StateFlow<ServerSettings> = _serverSettings.asStateFlow()

    /** Stable device UDN so renderers/TVs keep recognising this server across restarts. */
    val serverUdn: String
        get() = s.getStringOrNull(KEY_UDN) ?: "uuid:${MediaServer.randomUuid()}".also { s.putString(KEY_UDN, it) }

    override fun update(transform: (ServerSettings) -> ServerSettings) {
        val next = transform(_serverSettings.value).let { it.copy(name = it.name.trim().ifEmpty { defaultName }) }
        s.putString(KEY_NAME, next.name)
        s.putBoolean(KEY_AUTO_RUN, next.autoStart)
        s.putBoolean(KEY_VIDEO, next.shareVideo)
        s.putBoolean(KEY_AUDIO, next.shareAudio)
        s.putBoolean(KEY_IMAGE, next.shareImage)
        s.putString(KEY_FOLDERS, next.folders.joinToString("\n"))
        _serverSettings.value = next
    }

    private fun load() = ServerSettings(
        name = s.getString(KEY_NAME, defaultName),
        autoStart = s.getBoolean(KEY_AUTO_RUN, false),
        shareVideo = s.getBoolean(KEY_VIDEO, true),
        shareAudio = s.getBoolean(KEY_AUDIO, true),
        shareImage = s.getBoolean(KEY_IMAGE, true),
        folders = s.getStringOrNull(KEY_FOLDERS)?.split('\n')?.filter { it.isNotBlank() } ?: defaultSharedFolders(),
    )

    private companion object {
        const val KEY_NAME = "server_name"
        const val KEY_AUTO_RUN = "auto_run"
        const val KEY_VIDEO = "allow_video"
        const val KEY_AUDIO = "allow_audio"
        const val KEY_IMAGE = "allow_image"
        const val KEY_FOLDERS = "shared_folders"
        const val KEY_UDN = "server_udn"
    }
}

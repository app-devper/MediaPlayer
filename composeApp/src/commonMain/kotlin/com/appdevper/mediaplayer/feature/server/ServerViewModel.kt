package com.appdevper.mediaplayer.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.session.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.session.CastReceiverSession
import com.appdevper.mediaplayer.domain.session.MediaServerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ServerUiState(
    val status: ServerStatus,
    val settings: ServerSettings,
    val capabilities: ServerCapabilities,
    val permissionDenied: Boolean = false,
    val receiver: ReceiverStatus = ReceiverStatus.Stopped,
    val airPlay: AirPlayStatus = AirPlayStatus.Stopped,
) {
    val isOn: Boolean get() = status is ServerStatus.Running || status is ServerStatus.Starting
}

/** "My Server": the built-in DLNA server (old MediaServer app + settings screen) and receiving casts. */
class ServerViewModel(
    private val server: MediaServerSession,
    receiver: CastReceiverSession,
    airPlay: AirPlayReceiverSession,
) : ViewModel() {

    private val permissionDenied = MutableStateFlow(false)
    private val capabilities = server.capabilities

    val uiState: StateFlow<ServerUiState> = combine(
        server.status, server.serverSettings, permissionDenied, receiver.status, airPlay.status,
    ) { status, settings, denied, receiving, airPlaying ->
        ServerUiState(status, settings, capabilities, denied, receiving, airPlaying)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ServerUiState(server.status.value, server.serverSettings.value, capabilities, receiver = receiver.status.value, airPlay = airPlay.status.value),
    )

    /** Called after the platform permission prompt. */
    fun start(permissionGranted: Boolean) {
        permissionDenied.value = !permissionGranted
        if (permissionGranted) viewModelScope.launch { server.turnOn() }
    }

    fun stop() {
        viewModelScope.launch { server.turnOff() }
    }

    fun rename(name: String) = update { it.copy(name = name) }
    fun setAutoStart(on: Boolean) = update { it.copy(autoStart = on) }

    /** The receiver follows this setting by itself (see CastReceiverSession). */
    fun setReceiverEnabled(on: Boolean) = update { it.copy(receiverEnabled = on) }

    /** The AirPlay receiver follows these by itself (see AirPlayReceiverSession). */
    fun setAirPlayEnabled(on: Boolean) = update { it.copy(airPlayEnabled = on) }
    fun setAirPlayRequirePin(on: Boolean) = update { it.copy(airPlayRequirePin = on) }
    fun setShareVideo(on: Boolean) = update { it.copy(shareVideo = on) }
    fun setShareAudio(on: Boolean) = update { it.copy(shareAudio = on) }
    fun setShareImage(on: Boolean) = update { it.copy(shareImage = on) }
    fun addFolder(path: String) = update { s -> path.trim().takeIf { it.isNotEmpty() && it !in s.folders }?.let { s.copy(folders = s.folders + it) } ?: s }
    fun removeFolder(path: String) = update { it.copy(folders = it.folders - path) }

    /** A running server restarts by itself when what it publishes changes (see MediaServerSession). */
    private fun update(transform: (ServerSettings) -> ServerSettings) = server.update(transform)
}

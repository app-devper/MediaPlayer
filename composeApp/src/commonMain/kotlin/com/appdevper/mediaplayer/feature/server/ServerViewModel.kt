package com.appdevper.mediaplayer.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.ServerCapabilities
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import com.appdevper.mediaplayer.domain.usecase.GetServerCapabilitiesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerSettingsUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerStatusUseCase
import com.appdevper.mediaplayer.domain.usecase.StartMediaServerUseCase
import com.appdevper.mediaplayer.domain.usecase.StopMediaServerUseCase
import com.appdevper.mediaplayer.domain.usecase.UpdateServerSettingsUseCase
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
    observeStatus: ObserveServerStatusUseCase,
    observeSettings: ObserveServerSettingsUseCase,
    private val updateSettings: UpdateServerSettingsUseCase,
    private val startServer: StartMediaServerUseCase,
    private val stopServer: StopMediaServerUseCase,
    getCapabilities: GetServerCapabilitiesUseCase,
    receiver: CastReceiverSession,
    airPlay: AirPlayReceiverSession,
) : ViewModel() {

    private val permissionDenied = MutableStateFlow(false)
    private val capabilities = getCapabilities()

    val uiState: StateFlow<ServerUiState> = combine(
        observeStatus(), observeSettings(), permissionDenied, receiver.status, airPlay.status,
    ) { status, settings, denied, receiving, airPlaying ->
        ServerUiState(status, settings, capabilities, denied, receiving, airPlaying)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ServerUiState(observeStatus().value, observeSettings().value, capabilities, receiver = receiver.status.value, airPlay = airPlay.status.value),
    )

    /** Called after the platform permission prompt. */
    fun start(permissionGranted: Boolean) {
        permissionDenied.value = !permissionGranted
        if (permissionGranted) viewModelScope.launch { startServer() }
    }

    fun stop() {
        viewModelScope.launch { stopServer() }
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

    private fun update(transform: (ServerSettings) -> ServerSettings) {
        val restart = updateSettings(transform)
        if (restart && uiState.value.status is ServerStatus.Running) viewModelScope.launch { startServer() }
    }
}

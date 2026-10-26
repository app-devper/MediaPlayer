package com.appdevper.mediaplayer.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerStatusUseCase
import com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ShellUiState(
    val servers: List<MediaDevice> = emptyList(),
    val renderers: List<MediaDevice> = emptyList(),
    val searching: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val target: PlaybackTarget = PlaybackTarget.Local,
    val nowPlaying: MediaEntry? = null,
    val remotePlaying: Boolean = false,
)

/** State for the app chrome: sidebar, "Play on" picker and the mini player. */
class ShellViewModel(
    observeDevices: ObserveDevicesUseCase,
    observeServerStatus: ObserveServerStatusUseCase,
    private val refreshDevices: RefreshDevicesUseCase,
    private val session: PlaybackSession,
    receiver: CastReceiverSession,
) : ViewModel() {

    /** Another device cast something here: show the player. */
    val openPlayer: SharedFlow<Unit> = receiver.openPlayer

    val uiState: StateFlow<ShellUiState> = combine(
        observeDevices(),
        observeDevices.isSearching,
        observeServerStatus(),
        session.state,
    ) { devices, searching, server, playback ->
        ShellUiState(
            servers = devices.filter { it.isServer },
            renderers = devices.filter { it.isRenderer },
            searching = searching,
            serverStatus = server,
            target = playback.target,
            nowPlaying = playback.current,
            remotePlaying = playback.remote.status?.state == TransportState.PLAYING,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellUiState())

    fun refresh(clear: Boolean = false) = refreshDevices(clear)

    fun selectTarget(target: PlaybackTarget) = session.setTarget(target)

    fun togglePause() = session.togglePause()
}

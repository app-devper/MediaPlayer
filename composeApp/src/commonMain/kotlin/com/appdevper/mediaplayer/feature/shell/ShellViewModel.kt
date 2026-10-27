package com.appdevper.mediaplayer.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.usecase.AddServerByAddressUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerStatusUseCase
import com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShellUiState(
    val servers: List<MediaDevice> = emptyList(),
    val renderers: List<MediaDevice> = emptyList(),
    val searching: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val target: PlaybackTarget = PlaybackTarget.Local,
    val nowPlaying: MediaEntry? = null,
    val remotePlaying: Boolean = false,
    /**
     * Our own player still runs off the player screen (Android plays on in the background): playing
     * or paused. Null when there's nothing to control (the others stop their player off screen).
     */
    val localPlaying: Boolean? = null,
    val airPlay: AirPlayActivity = AirPlayActivity(),
    /** Adding a media server by its address (Home › Media servers). */
    val addServer: AddServerState = AddServerState.Idle,
) {
    /** Something to show under "Now playing": our own item, or audio a sender plays here. */
    val hasNowPlaying: Boolean get() = nowPlaying != null || airPlay.audioOnly
}

/** State for the app chrome: sidebar, "Play on" picker and the mini player. */
class ShellViewModel(
    observeDevices: ObserveDevicesUseCase,
    observeServerStatus: ObserveServerStatusUseCase,
    private val refreshDevices: RefreshDevicesUseCase,
    private val addServerByAddress: AddServerByAddressUseCase,
    private val session: PlaybackSession,
    receiver: CastReceiverSession,
    airPlay: AirPlayReceiverSession,
) : ViewModel() {

    /** Another device cast something here (DLNA or AirPlay video): show the player. */
    val openPlayer: Flow<Unit> = merge(receiver.openPlayer, airPlay.openPlayer)

    /** An iPhone/iPad/Mac started mirroring: show it. */
    val openMirror: SharedFlow<Unit> = airPlay.openMirror

    private val addServer = MutableStateFlow<AddServerState>(AddServerState.Idle)

    val uiState: StateFlow<ShellUiState> = combine(
        observeDevices(),
        observeDevices.isSearching,
        observeServerStatus(),
        session.state,
        airPlay.activity,
    ) { devices, searching, server, playback, airPlayActivity ->
        ShellUiState(
            servers = devices.filter { it.isServer },
            renderers = devices.filter { it.isRenderer },
            searching = searching,
            serverStatus = server,
            target = playback.target,
            nowPlaying = playback.current,
            remotePlaying = playback.remote.status?.state == TransportState.PLAYING,
            localPlaying = when (playback.local?.state.takeIf { playback.target == PlaybackTarget.Local }) {
                TransportState.PLAYING, TransportState.TRANSITIONING -> true
                TransportState.PAUSED -> false
                else -> null
            },
            airPlay = airPlayActivity,
        )
    }.combine(addServer) { state, adding -> state.copy(addServer = adding) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellUiState())

    fun refresh(clear: Boolean = false) = refreshDevices(clear)

    /** Asks [address] directly for a media server (when discovery can't find it). */
    fun addServer(address: String) {
        if (addServer.value is AddServerState.Working) return
        addServer.value = AddServerState.Working
        adding = viewModelScope.launch {
            val found = runCatching { addServerByAddress(address) }.getOrDefault(emptyList())
            addServer.value = if (found.isEmpty()) AddServerState.NotFound(address.trim()) else AddServerState.Added(found.map { it.name })
        }
    }

    /** Cancel, or a fresh start: a search still running stops (it won't add or report anything later). */
    fun dismissAddServer() {
        adding?.cancel()
        adding = null
        addServer.value = AddServerState.Idle
    }

    private var adding: Job? = null

    fun selectTarget(target: PlaybackTarget) = session.setTarget(target)

    fun togglePause() = session.togglePause()

    fun toggleLocal() = session.sendLocal(if (uiState.value.localPlaying == true) LocalPlayerCommand.Pause else LocalPlayerCommand.Play)
}

sealed interface AddServerState {
    data object Idle : AddServerState
    data object Working : AddServerState
    data class NotFound(val address: String) : AddServerState
    data class Added(val names: List<String>) : AddServerState
}

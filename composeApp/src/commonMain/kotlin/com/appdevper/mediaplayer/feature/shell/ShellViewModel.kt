package com.appdevper.mediaplayer.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.ServerStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.playback.AirPlayReceiverSession
import com.appdevper.mediaplayer.domain.playback.CastReceiverSession
import com.appdevper.mediaplayer.domain.playback.PlaybackSession
import com.appdevper.mediaplayer.domain.usecase.ObserveDevicesUseCase
import com.appdevper.mediaplayer.domain.usecase.ObserveServerStatusUseCase
import com.appdevper.mediaplayer.domain.usecase.RefreshDevicesUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

data class ShellUiState(
    val servers: List<MediaDevice> = emptyList(),
    val renderers: List<MediaDevice> = emptyList(),
    val searching: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val target: PlaybackTarget = PlaybackTarget.Local,
    val nowPlaying: MediaEntry? = null,
    val remotePlaying: Boolean = false,
    val airPlay: AirPlayActivity = AirPlayActivity(),
)

/** State for the app chrome: sidebar, "Play on" picker and the mini player. */
class ShellViewModel(
    observeDevices: ObserveDevicesUseCase,
    observeServerStatus: ObserveServerStatusUseCase,
    private val refreshDevices: RefreshDevicesUseCase,
    private val session: PlaybackSession,
    receiver: CastReceiverSession,
    airPlay: AirPlayReceiverSession,
) : ViewModel() {

    /** Another device cast something here (DLNA or AirPlay video): show the player. */
    val openPlayer: Flow<Unit> = merge(receiver.openPlayer, airPlay.openPlayer)

    /** An iPhone/iPad/Mac started mirroring: show it. */
    val openMirror: SharedFlow<Unit> = airPlay.openMirror

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
            airPlay = airPlayActivity,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellUiState())

    fun refresh(clear: Boolean = false) = refreshDevices(clear)

    fun selectTarget(target: PlaybackTarget) = session.setTarget(target)

    fun togglePause() = session.togglePause()
}

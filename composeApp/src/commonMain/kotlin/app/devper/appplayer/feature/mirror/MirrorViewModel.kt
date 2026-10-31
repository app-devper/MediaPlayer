package app.devper.appplayer.feature.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.devper.appplayer.airplay.AirPlayPlayer
import app.devper.appplayer.airplay.VideoSize
import app.devper.appplayer.domain.model.AirPlayActivity
import app.devper.appplayer.domain.model.AirPlayStatus
import app.devper.appplayer.domain.session.AirPlayReceiverSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MirrorUiState(
    val status: AirPlayStatus = AirPlayStatus.Stopped,
    val activity: AirPlayActivity = AirPlayActivity(),
    val videoSize: VideoSize? = null,
)

/** AirPlay screen mirroring / audio from a sender. */
class MirrorViewModel(session: AirPlayReceiverSession, val player: AirPlayPlayer) : ViewModel() {
    val uiState: StateFlow<MirrorUiState> = combine(session.status, session.activity, player.videoSize, ::MirrorUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MirrorUiState(session.status.value, session.activity.value, player.videoSize.value))
}

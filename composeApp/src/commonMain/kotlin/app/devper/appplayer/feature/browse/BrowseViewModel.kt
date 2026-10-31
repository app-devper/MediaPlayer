package app.devper.appplayer.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.session.PlaybackSession
import app.devper.appplayer.domain.usecase.BrowseFolderUseCase
import app.devper.appplayer.domain.usecase.FilterEntriesUseCase
import app.devper.appplayer.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val title: String,
    val deviceName: String,
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val all: List<MediaEntry> = emptyList(),
    val visible: List<MediaEntry> = emptyList(),
) {
    /** Photo folders are shown as a grid. */
    val isPhotoGrid: Boolean get() = all.isNotEmpty() && all.count { it.kind == MediaKind.IMAGE } > all.size / 2
    val isEmpty: Boolean get() = !loading && error == null && all.isEmpty()
}

class BrowseViewModel(
    private val deviceId: String,
    private val folderId: String,
    title: String,
    devices: DeviceRepository,
    private val browseFolder: BrowseFolderUseCase,
    private val filterEntries: FilterEntriesUseCase,
    private val session: PlaybackSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState(title = title, deviceName = devices.device(deviceId)?.name.orEmpty()))
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            browseFolder(deviceId, folderId).fold(
                onSuccess = { entries ->
                    _uiState.update { it.copy(loading = false, all = entries, visible = filterEntries(entries, it.query)) }
                },
                onFailure = { e -> _uiState.update { it.copy(loading = false, error = e.message ?: "Couldn't open this folder") } },
            )
        }
    }

    fun onQueryChange(query: String) = _uiState.update { it.copy(query = query, visible = filterEntries(it.all, query)) }

    /** Starts playback; returns true when the caller should open the player. */
    fun play(entry: MediaEntry): Boolean {
        if (!entry.isPlayable) return false
        session.play(entry, _uiState.value.all)
        return true
    }
}

package com.appdevper.mediaplayer.domain.session

import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleChoice
import com.appdevper.mediaplayer.domain.model.SubtitleCue
import com.appdevper.mediaplayer.domain.model.SubtitleState
import com.appdevper.mediaplayer.domain.model.textAt
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.subtitle.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [LocalPlayer]'s subtitles: one [SubtitleChoice] for the loaded item, kept through a reload of the
 * same item, none for another. A file is fetched and read here (off [scope]'s thread, on [parsing]);
 * a track is the engine's to show or read. A track picked in the platform's own controls (AVKit's
 * menu) becomes the choice. Runs on the player's thread, called by the player.
 */
internal class LocalSubtitles(
    private val engine: PlaybackEngine,
    private val files: SubtitleRepository,
    private val scope: CoroutineScope,
    private val parsing: CoroutineDispatcher,
) {
    val state = MutableStateFlow(SubtitleState())

    /** The line on screen now, for us to draw (an engine that draws its track itself gives none). */
    val text = MutableStateFlow<String?>(null)

    private var cues: List<SubtitleCue> = emptyList()
    private var fileJob: Job? = null

    // The engine loses the track on every load: until it knows the tracks, the choice isn't applied yet.
    private var trackApplied = false

    fun pick(choice: SubtitleChoice) {
        fileJob?.cancel()
        cues = emptyList()
        trackApplied = false
        if (choice !is SubtitleChoice.File) {
            state.value = SubtitleState(choice)
            return
        }
        state.value = SubtitleState(choice, loading = true)
        fileJob = scope.launch {
            val read = read(choice.subtitle)
            if (read.isNullOrEmpty()) {
                state.value = SubtitleState(error = "Couldn't load the subtitles (${choice.subtitle.label})")
            } else {
                cues = read
                state.value = SubtitleState(choice)
            }
        }
    }

    private suspend fun read(subtitle: Subtitle): List<SubtitleCue>? =
        try {
            withContext(parsing) { SubtitleParser.parse(files.fetch(subtitle.url)) }
        } catch (e: CancellationException) {
            throw e // another pick
        } catch (e: Throwable) {
            null
        }

    /** The player loaded [sameItem] again (repeat one, back from a renderer, after an error) or another item. */
    fun onLoad(sameItem: Boolean) {
        if (sameItem) trackApplied = false else pick(SubtitleChoice.Off)
    }

    /** Nothing in the player (a photo, or playing on a renderer): no line; the choice waits for the item to come back. */
    fun onUnload() {
        text.value = null
        trackApplied = false
    }

    /**
     * Each poll. Once the engine knows the tracks, applies the choice (a file's default track doesn't
     * show by itself); after that, a different track showing was picked in the platform's own controls.
     * Works out the line on screen. Returns the facts after any change.
     */
    fun sync(facts: EngineFacts): EngineFacts {
        var now = facts
        val choice = state.value.choice
        val wanted = (choice as? SubtitleChoice.Track)?.track?.id
        if (!trackApplied) {
            if (now.textTracks.isNotEmpty()) {
                engine.selectTextTrack(wanted?.takeIf { id -> now.textTracks.any { it.id == id } })
                trackApplied = true
                now = engine.facts()
            }
        } else if (now.selectedTextTrack != wanted) {
            val track = now.textTracks.firstOrNull { it.id == now.selectedTextTrack }
            when {
                track != null -> {
                    fileJob?.cancel()
                    cues = emptyList()
                    state.value = SubtitleState(SubtitleChoice.Track(track))
                }
                choice is SubtitleChoice.Track -> state.value = SubtitleState()
            }
        }
        text.value = when (state.value.choice) {
            SubtitleChoice.Off -> null
            is SubtitleChoice.File -> cues.textAt(now.positionMs)
            is SubtitleChoice.Track -> now.subtitleText
        }
        return now
    }
}

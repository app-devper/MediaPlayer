package com.appdevper.mediaplayer.domain.model

/** A subtitle file for a video, such as `Movie.th.srt` next to `Movie.mkv`. */
data class Subtitle(
    val url: String,
    /** What to call it in the list: the language, or the file name. */
    val label: String,
    /** Language code from the file name (`th`, `en`), when it has one. */
    val language: String? = null,
)

/** A subtitle track inside the media file itself (MKV, MP4), as its player lists it. */
data class TextTrack(
    /** The player's own id for it. */
    val id: String,
    val label: String,
    val language: String? = null,
)

/** The subtitle on this device's player: none, a file next to the video, or a track inside it. */
sealed interface SubtitleChoice {
    data object Off : SubtitleChoice
    data class File(val subtitle: Subtitle) : SubtitleChoice
    data class Track(val track: TextTrack) : SubtitleChoice
}

/** The [SubtitleChoice], whether its file is still being read, and why one couldn't be read. */
data class SubtitleState(
    val choice: SubtitleChoice = SubtitleChoice.Off,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One line (or a few) on screen from [startMs] until [endMs]. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** The text on screen at [positionMs]: every cue on then (overlapping ones too), in order; null for none. */
fun List<SubtitleCue>.textAt(positionMs: Long): String? {
    // Cues are sorted by start: only those that started by now can be on.
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) / 2
        if (this[mid].startMs <= positionMs) low = mid + 1 else high = mid
    }
    return subList(0, low).filter { positionMs < it.endMs }.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
}

package com.appdevper.mediaplayer.domain.model

/** A subtitle file for a video, such as `Movie.th.srt` next to `Movie.mkv`. */
data class Subtitle(
    val url: String,
    /** What to call it in the list: the language, or the file name. */
    val label: String,
    /** Language code from the file name (`th`, `en`), when it has one. */
    val language: String? = null,
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

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

/** The cue on screen at [positionMs], if any. [cues] are sorted by start. */
fun List<SubtitleCue>.at(positionMs: Long): SubtitleCue? {
    var low = 0
    var high = lastIndex
    var found: SubtitleCue? = null
    // The last cue that started by now, then whether it's still on.
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].startMs <= positionMs) {
            found = this[mid]
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found?.takeIf { positionMs < it.endMs }
}

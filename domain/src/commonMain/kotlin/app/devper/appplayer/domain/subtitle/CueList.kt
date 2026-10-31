package app.devper.appplayer.domain.subtitle

import app.devper.appplayer.domain.model.SubtitleCue
import app.devper.appplayer.domain.model.textAt
import kotlin.concurrent.Volatile

/**
 * The cues of a subtitle track read as the video plays (a track inside the file), kept in start
 * order: one writer adds them, anyone reads [textAt]. A cue read again (after a seek back) isn't
 * added twice; one with no end lasts until the next one starts, and at most [noEndMs].
 */
class CueList(private val noEndMs: Long = 5_000) {
    @Volatile var cues: List<SubtitleCue> = emptyList()
        private set

    // The last cue added without an end of its own: the next one ends it.
    private var open: SubtitleCue? = null

    /** [text] from [startMs] to [endMs]; an [endMs] not after [startMs] means it has none. */
    fun add(startMs: Long, endMs: Long, text: String) {
        open?.let { o -> if (o.startMs < startMs && o.endMs > startMs) replace(o, o.copy(endMs = startMs)) }
        val cue = SubtitleCue(startMs, if (endMs > startMs) endMs else startMs + noEndMs, text)
        open = cue.takeIf { endMs <= startMs }
        insert(cue)
    }

    fun textAt(positionMs: Long): String? = cues.textAt(positionMs)

    // Mostly an append, since packets come in order.
    private fun insert(cue: SubtitleCue) {
        val cues = cues
        var at = cues.size
        while (at > 0 && cues[at - 1].startMs > cue.startMs) at--
        var i = at - 1
        while (i >= 0 && cues[i].startMs == cue.startMs) if (cues[i--].text == cue.text) return
        this.cues = ArrayList<SubtitleCue>(cues.size + 1).apply {
            addAll(cues.subList(0, at))
            add(cue)
            addAll(cues.subList(at, cues.size))
        }
    }

    private fun replace(old: SubtitleCue, new: SubtitleCue) {
        cues = cues.map { if (it === old) new else it }
    }
}

package app.devper.appplayer.domain.subtitle

import app.devper.appplayer.domain.model.SubtitleCue

/** Reads SubRip (.srt) and WebVTT (.vtt) files into cues, sorted by start. */
object SubtitleParser {

    // Bounded and anchored, so a hostile file can't make them backtrack for long.
    private val timing = Regex("""^\s*(\d{1,4}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,4}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val tags = Regex("""</?[a-zA-Z][^>\n]{0,256}>|\{\\[^}\n]{0,256}}""")

    /** Longer lines are never a timing line. */
    private const val MAX_TIMING_LINE = 200

    /** [bytes] in UTF-8, UTF-16, Thai TIS-620 / Windows-874 or Windows-1252 ([decode]). */
    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(decode(bytes))

    /**
     * Line by line: a timing line starts a cue, whose text runs to the next blank line or timing line.
     * A number on its own just before a timing line is the next cue's index, not text (some files
     * leave out the blank line between cues). Anything else outside a cue (WEBVTT, NOTE, cue ids) is skipped.
     */
    fun parse(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var start = 0L
        var end = 0L
        var body: MutableList<String>? = null
        fun finish() {
            val lines = body ?: return
            val text = lines.joinToString("\n") { it.replace(tags, "").trim() }.trim()
            if (text.isNotEmpty() && end > start) cues += SubtitleCue(start, end, unescape(text))
            body = null
        }
        for (line in text.lineSequence().map { it.trimEnd('\r') }) {
            val m = if (line.length <= MAX_TIMING_LINE) timing.find(line) else null
            when {
                m != null -> {
                    body?.let { lines -> if (lines.lastOrNull()?.trim()?.let { it.isNotEmpty() && it.all(Char::isDigit) } == true) lines.removeAt(lines.lastIndex) }
                    finish()
                    val g = m.groupValues
                    start = ms(g[1], g[2], g[3], g[4])
                    end = ms(g[5], g[6], g[7], g[8])
                    body = mutableListOf()
                }
                line.isBlank() -> finish()
                else -> body?.add(line)
            }
        }
        finish()
        return cues.sortedBy { it.startMs }
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.removeSuffix(":").toLongOrNull() ?: 0
        val millis = frac.padEnd(3, '0').toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    /**
     * The text of an ASS/SSA event as FFmpeg gives it (`ReadOrder,Layer,Style,Name,MarginL,MarginR,
     * MarginV,Effect,Text`, or a full `Dialogue:` line): its override tags removed, \N as new lines.
     */
    fun assText(event: String): String {
        // A Dialogue line has 9 fields before the text (with start and end), FFmpeg's events 8. The text
        // is all the rest (it may hold commas itself).
        val dialogue = event.trimStart().startsWith("Dialogue:")
        val fields = event.trimStart().removePrefix("Dialogue:").trim()
        var at = 0
        repeat(if (dialogue) 9 else 8) { at = fields.indexOf(',', at).takeIf { it >= 0 }?.plus(1) ?: return fields.trim() }
        return fields.substring(at).replace(assTags, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
    }

    private val assTags = Regex("""\{[^}\n]{0,256}}""")

    // &amp; last, so "&amp;lt;" stays "&lt;".
    private fun unescape(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    /**
     * Text from a subtitle file's bytes: UTF-8 or UTF-16 by their BOM, UTF-16 without one by its zero
     * bytes, UTF-8 when it is valid; else Thai TIS-620 / Windows-874 when most letters are Thai bytes
     * (Thai subtitles are often saved that way), and Windows-1252 otherwise.
     */
    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> bytes.decodeToString(3, bytes.size)
        bytes.startsWith(0xFF, 0xFE) -> utf16(bytes, 2, littleEndian = true)
        bytes.startsWith(0xFE, 0xFF) -> utf16(bytes, 2, littleEndian = false)
        else -> utf16WithoutBom(bytes)
            ?: runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: if (looksThai(bytes)) windows874(bytes) else windows1252(bytes)
    }

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xFF == prefix[it] }

    private fun utf16(bytes: ByteArray, from: Int, littleEndian: Boolean): String = buildString {
        var i = from
        while (i + 1 < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            append((if (littleEndian) a or (b shl 8) else (a shl 8) or b).toChar())
            i += 2
        }
    }

    /** Text in UTF-16 has a zero in most of its odd (little-endian) or even (big-endian) bytes. */
    private fun utf16WithoutBom(bytes: ByteArray): String? {
        val sample = minOf(bytes.size, 4096) / 2 * 2
        if (sample < 8) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sample) if (bytes[i].toInt() == 0) if (i % 2 == 0) evenZeros++ else oddZeros++
        val half = sample / 2
        return when {
            oddZeros > half * 0.4 && evenZeros < half * 0.05 -> utf16(bytes, 0, littleEndian = true)
            evenZeros > half * 0.4 && oddZeros < half * 0.05 -> utf16(bytes, 0, littleEndian = false)
            else -> null
        }
    }

    /** Thai text is mostly Thai letters (0xA1–0xFB); a Latin text's accents are a few among ASCII letters. */
    private fun looksThai(bytes: ByteArray): Boolean {
        var thai = 0
        var latin = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0xA1..0xFB) thai++ else if (c in 0x41..0x5A || c in 0x61..0x7A) latin++
        }
        return thai > 0 && thai * 2 >= latin
    }

    /** TIS-620 / Windows-874: bytes 0xA1–0xFB are Thai letters (U+0E01–U+0E5B). */
    private fun windows874(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            append(
                when (c) {
                    in 0xA1..0xFB -> (c + 0x0D60).toChar()
                    in 0x80..0x9F -> cp1252High[c - 0x80]
                    else -> c.toChar()
                },
            )
        }
    }

    private fun windows1252(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            append(if (c in 0x80..0x9F) cp1252High[c - 0x80] else c.toChar())
        }
    }

    /** Windows-1252's 0x80–0x9F (the rest of its upper half is Latin-1). */
    private const val cp1252High = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"
}

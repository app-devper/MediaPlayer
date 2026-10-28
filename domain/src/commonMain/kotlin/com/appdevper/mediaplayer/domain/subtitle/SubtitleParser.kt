package com.appdevper.mediaplayer.domain.subtitle

import com.appdevper.mediaplayer.domain.model.SubtitleCue

/** Reads SubRip (.srt) and WebVTT (.vtt) files into cues, sorted by start. */
object SubtitleParser {

    private val timing = Regex("""(\d+:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+:)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val tags = Regex("""<[^>]*>|\{\\[^}]*\}""")

    /** [bytes] in UTF-8, UTF-16 (with a BOM) or, failing UTF-8, Thai TIS-620 / Windows-874. */
    fun parse(bytes: ByteArray): List<SubtitleCue> = parse(decode(bytes))

    fun parse(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        // Blocks are separated by blank lines; in each, the timing line, then the text up to the block's end.
        for (block in text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val at = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (at < 0) continue
            val m = timing.find(lines[at]) ?: continue
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            val body = lines.drop(at + 1).joinToString("\n") { it.replace(tags, "").trim() }.trim()
            if (body.isNotEmpty() && end > start) cues += SubtitleCue(start, end, unescape(body))
        }
        return cues.sortedBy { it.startMs }
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.removeSuffix(":").toLongOrNull() ?: 0
        val millis = frac.padEnd(3, '0').toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    private fun unescape(text: String) =
        text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    /** Text from a subtitle file's bytes. */
    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> bytes.decodeToString(3, bytes.size)
        bytes.startsWith(0xFF, 0xFE) -> utf16(bytes, 2, littleEndian = true)
        bytes.startsWith(0xFE, 0xFF) -> utf16(bytes, 2, littleEndian = false)
        else -> runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse { windows874(bytes) }
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

    /**
     * Thai subtitles are often saved in TIS-620 / Windows-874: bytes 0xA1–0xFB are Thai letters
     * (U+0E01–U+0E5B). Other bytes above ASCII are read as Latin-1.
     */
    private fun windows874(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            append(
                when (c) {
                    in 0xA1..0xFB -> (c + 0x0D60).toChar()
                    0x80 -> '€'
                    0x85 -> '…'
                    0x91 -> '‘'
                    0x92 -> '’'
                    0x93 -> '“'
                    0x94 -> '”'
                    0x95 -> '•'
                    0x96 -> '–'
                    0x97 -> '—'
                    else -> c.toChar()
                },
            )
        }
    }
}

package app.devper.appplayer.domain.subtitle

/** Sidecar subtitle files: `Movie.srt`, `Movie.th.srt`, `Movie.English.forced.vtt` next to `Movie.mkv`. */
object SubtitleFiles {

    private val extensions = setOf("srt", "vtt")
    private val flags = setOf("forced", "sdh", "cc", "hi")

    fun isSubtitle(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in extensions

    /**
     * The files among [siblings] that belong to [videoFileName], with the language each names. A file
     * goes to the video whose name it matches most closely: with `Movie.mkv` and `Movie.2020.mkv`
     * side by side, `Movie.2020.th.srt` is the second one's. After the video's name comes at most a
     * language, and maybe a flag (`forced`, `sdh`).
     */
    fun sidecars(videoFileName: String, siblings: List<String>): List<Sidecar> {
        val base = videoFileName.substringBeforeLast('.').lowercase()
        val otherBases = siblings.filterNot(::isSubtitle).map { it.substringBeforeLast('.').lowercase() }.toSet()
        return siblings.filter(::isSubtitle).mapNotNull { name ->
            val stem = name.substringBeforeLast('.').lowercase()
            val owner = (otherBases + base).filter { stem == it || stem.startsWith("$it.") }.maxByOrNull { it.length }
            if (owner != base) return@mapNotNull null
            if (stem == base) return@mapNotNull Sidecar(name, null)
            val rest = name.substringBeforeLast('.').substring(base.length + 1).split('.')
            when {
                rest.size == 1 && rest[0].isNotBlank() -> Sidecar(name, rest[0])
                rest.size == 2 && rest[1].lowercase() in flags && rest[0].isNotBlank() -> Sidecar(name, rest[0])
                else -> null
            }
        }.sortedWith(compareBy({ it.language != null }, { it.fileName.lowercase() }))
    }

    /** What to show for a subtitle: its language's name, or the file name. */
    fun label(language: String?, fileName: String): String =
        language?.let { languages[it.lowercase()] ?: it } ?: fileName

    /** A known language in a subtitle file name or URL: `Movie.th.srt`, `Movie.English.forced.srt`; none for `Movie.HD.srt`. */
    fun languageOf(fileName: String): String? {
        // Name parts without the extension: [Movie, English, forced].
        val parts = fileName.substringAfterLast('/').substringBefore('?').split('.').dropLast(1)
        if (parts.size < 2) return null
        val language = if (parts.last().lowercase() in flags && parts.size >= 3) parts[parts.size - 2] else parts.last()
        return language.takeIf { it.lowercase() in languages }
    }

    data class Sidecar(val fileName: String, val language: String?)

    private val languages = mapOf(
        "th" to "Thai", "tha" to "Thai", "thai" to "Thai", "en" to "English", "eng" to "English", "english" to "English",
        "ja" to "Japanese", "jpn" to "Japanese", "japanese" to "Japanese", "ko" to "Korean", "kor" to "Korean", "korean" to "Korean",
        "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese", "chinese" to "Chinese",
        "fr" to "French", "fre" to "French", "fra" to "French", "french" to "French",
        "de" to "German", "ger" to "German", "deu" to "German", "german" to "German",
        "es" to "Spanish", "spa" to "Spanish", "spanish" to "Spanish",
        "vi" to "Vietnamese", "vie" to "Vietnamese", "vietnamese" to "Vietnamese",
        "id" to "Indonesian", "ind" to "Indonesian", "indonesian" to "Indonesian",
        "ms" to "Malay", "msa" to "Malay", "malay" to "Malay", "lo" to "Lao", "lao" to "Lao",
    )
}

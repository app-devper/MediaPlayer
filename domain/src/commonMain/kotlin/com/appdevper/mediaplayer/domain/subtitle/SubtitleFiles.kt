package com.appdevper.mediaplayer.domain.subtitle

/** Sidecar subtitle files: `Movie.srt`, `Movie.th.srt`, `Movie.English.vtt` next to `Movie.mkv`. */
object SubtitleFiles {

    private val extensions = setOf("srt", "vtt")

    fun isSubtitle(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in extensions

    /** The files among [siblings] that belong to [videoFileName], with the language each names. */
    fun sidecars(videoFileName: String, siblings: List<String>): List<Sidecar> {
        val base = videoFileName.substringBeforeLast('.').lowercase()
        return siblings.filter(::isSubtitle).mapNotNull { name ->
            val stem = name.substringBeforeLast('.')
            when {
                stem.lowercase() == base -> Sidecar(name, null)
                stem.lowercase().startsWith("$base.") -> Sidecar(name, stem.substring(base.length + 1).takeIf { it.isNotBlank() })
                else -> null
            }
        }.sortedWith(compareBy({ it.language != null }, { it.fileName.lowercase() }))
    }

    /** What to show for a subtitle: its language's name, or the file name. */
    fun label(language: String?, fileName: String): String =
        language?.let { languages[it.lowercase()] ?: it } ?: fileName

    /** Language from a subtitle file name or URL: `Movie.th.srt` → `th`; none for `Movie.srt`. */
    fun languageOf(fileName: String): String? {
        val parts = fileName.substringAfterLast('/').substringBefore('?').split('.')
        return parts.getOrNull(parts.size - 2)?.takeIf { parts.size >= 3 && it.length in 2..3 && it.all(Char::isLetter) }
    }

    data class Sidecar(val fileName: String, val language: String?)

    private val languages = mapOf(
        "th" to "Thai", "tha" to "Thai", "en" to "English", "eng" to "English", "ja" to "Japanese", "jpn" to "Japanese",
        "ko" to "Korean", "kor" to "Korean", "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
        "fr" to "French", "fre" to "French", "de" to "German", "ger" to "German", "es" to "Spanish", "spa" to "Spanish",
        "vi" to "Vietnamese", "vie" to "Vietnamese", "id" to "Indonesian", "ms" to "Malay", "lo" to "Lao",
    )
}

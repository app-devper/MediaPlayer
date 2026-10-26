package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.MediaEntry

/** Filters a folder listing by a free-text query (title, artist, album). */
class FilterEntriesUseCase {
    operator fun invoke(entries: List<MediaEntry>, query: String): List<MediaEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { e ->
            listOfNotNull(e.title, e.artist, e.album).any { it.contains(q, ignoreCase = true) }
        }
    }
}

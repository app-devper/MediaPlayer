package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.repository.LibraryRepository

/** Lists a folder: sub-folders first, then media, both alphabetically when the server doesn't sort. */
class BrowseFolderUseCase(private val repo: LibraryRepository) {
    suspend operator fun invoke(deviceId: String, folderId: String): Result<List<MediaEntry>> =
        runCatching { repo.browse(deviceId, folderId) }
            .map { entries -> entries.sortedWith(compareBy<MediaEntry> { !it.isFolder }) }
}

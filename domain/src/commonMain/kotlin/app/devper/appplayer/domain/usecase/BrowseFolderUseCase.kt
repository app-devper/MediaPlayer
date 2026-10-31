package app.devper.appplayer.domain.usecase

import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.repository.LibraryRepository

/** Lists a folder: sub-folders first, then media, both alphabetically when the server doesn't sort. */
class BrowseFolderUseCase(private val repo: LibraryRepository) {
    suspend operator fun invoke(deviceId: String, folderId: String): Result<List<MediaEntry>> =
        runCatching { repo.browse(deviceId, folderId) }
            .map { entries -> entries.sortedWith(compareBy<MediaEntry> { !it.isFolder }) }
}

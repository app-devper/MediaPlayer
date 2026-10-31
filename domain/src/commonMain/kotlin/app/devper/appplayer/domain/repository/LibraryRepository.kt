package app.devper.appplayer.domain.repository

import app.devper.appplayer.domain.model.MediaEntry

/** Browsing the content of a media server. */
interface LibraryRepository {
    suspend fun browse(deviceId: String, folderId: String): List<MediaEntry>
}

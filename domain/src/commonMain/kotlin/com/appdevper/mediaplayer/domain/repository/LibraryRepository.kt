package com.appdevper.mediaplayer.domain.repository

import com.appdevper.mediaplayer.domain.model.MediaEntry

/** Browsing the content of a media server. */
interface LibraryRepository {
    suspend fun browse(deviceId: String, folderId: String): List<MediaEntry>
}

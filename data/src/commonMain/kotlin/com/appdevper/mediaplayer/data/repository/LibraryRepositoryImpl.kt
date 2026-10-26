package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.upnp.ContentDirectoryClient
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.data.upnp.UpnpException
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.repository.LibraryRepository

class LibraryRepositoryImpl(
    private val controlPoint: ControlPoint,
    private val contentDirectory: ContentDirectoryClient,
) : LibraryRepository {
    override suspend fun browse(deviceId: String, folderId: String): List<MediaEntry> {
        val device = controlPoint.device(deviceId) ?: throw UpnpException("This server is no longer available.")
        return contentDirectory.browseChildren(device, folderId).objects.map { it.toDomain() }
    }
}

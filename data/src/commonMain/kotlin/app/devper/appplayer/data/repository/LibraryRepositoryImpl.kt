package app.devper.appplayer.data.repository

import app.devper.appplayer.data.mapper.toDomain
import app.devper.appplayer.data.upnp.ContentDirectoryClient
import app.devper.appplayer.data.upnp.ControlPoint
import app.devper.appplayer.data.upnp.UpnpException
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.repository.LibraryRepository

class LibraryRepositoryImpl(
    private val controlPoint: ControlPoint,
    private val contentDirectory: ContentDirectoryClient,
) : LibraryRepository {
    override suspend fun browse(deviceId: String, folderId: String): List<MediaEntry> {
        val device = controlPoint.device(deviceId) ?: throw UpnpException("This server is no longer available.")
        return contentDirectory.browseChildren(device, folderId).objects.map { it.toDomain() }
    }
}

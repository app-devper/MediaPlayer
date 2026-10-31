package app.devper.appplayer.data.platform

import app.devper.appplayer.domain.model.ServerCapabilities
import java.io.File

actual val platformName: String = System.getProperty("os.name") ?: "Desktop"

actual fun defaultSharedFolders(): List<String> {
    val home = System.getProperty("user.home")
    return listOf("Movies", "Videos", "Music", "Pictures")
        .map { File(home, it) }
        .filter { it.isDirectory }
        .map { it.absolutePath }
}

class FolderLibraryFactory : MediaLibraryFactory {
    override val capabilities = ServerCapabilities(canChooseFolders = true, sourceDescription = "the folders listed below")
    override fun create(folders: List<String>): MediaLibrary = FileMediaLibrary(folders)
}

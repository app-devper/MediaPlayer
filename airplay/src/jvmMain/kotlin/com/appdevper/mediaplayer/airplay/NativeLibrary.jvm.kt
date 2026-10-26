package com.appdevper.mediaplayer.airplay

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The desktop build puts the library for the build machine's OS/arch in the jar under
 * native/<os>-<arch>/; it is copied to a temp file because System.load needs a real path.
 */
internal actual fun loadNativeLibrary() {
    val name = System.mapLibraryName("mediaplayer_airplay")
    val resource = "/native/${hostPlatform()}/$name"
    val stream = NativeAirPlay::class.java.getResourceAsStream(resource)
        ?: throw UnsatisfiedLinkError("AirPlay library not bundled for ${hostPlatform()} ($resource)")
    val dir = Files.createTempDirectory("mediaplayer-airplay").toFile().apply { deleteOnExit() }
    val file = File(dir, name).apply { deleteOnExit() }
    stream.use { Files.copy(it, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    System.load(file.absolutePath)
}

internal fun hostPlatform(): String {
    val os = System.getProperty("os.name").lowercase().let {
        when {
            "mac" in it -> "macos"
            "win" in it -> "windows"
            else -> "linux"
        }
    }
    val arch = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
    return "$os-$arch"
}

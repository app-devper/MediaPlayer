package com.appdevper.mediaplayer.data.platform

import com.appdevper.mediaplayer.domain.model.MediaKind

object MimeTypes {
    private val byExtension = mapOf(
        // video
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ts" to "video/mp2t", "m2ts" to "video/mp2t",
        "3gp" to "video/3gpp", "flv" to "video/x-flv",
        // audio
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/opus", "wma" to "audio/x-ms-wma",
        "aiff" to "audio/aiff", "aif" to "audio/aiff", "alac" to "audio/mp4",
        // image
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "bmp" to "image/bmp", "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
    )

    fun fromFileName(name: String): String? = byExtension[name.substringAfterLast('.', "").lowercase()]

    fun kindOf(mime: String): MediaKind = when {
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> MediaKind.OTHER
    }

    fun extensionFor(mime: String, source: String): String {
        val fromSource = source.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (fromSource.isNotEmpty() && fromSource.length <= 5 && fromSource.all { it.isLetterOrDigit() }) return fromSource
        return byExtension.entries.firstOrNull { it.value == mime }?.key ?: "bin"
    }
}

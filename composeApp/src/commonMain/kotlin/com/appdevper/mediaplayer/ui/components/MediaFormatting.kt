package com.appdevper.mediaplayer.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.ui.icons.AppIcons

fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.FOLDER -> AppIcons.Folder
    MediaKind.VIDEO -> AppIcons.Film
    MediaKind.AUDIO -> AppIcons.Music
    MediaKind.IMAGE -> AppIcons.Image
    MediaKind.OTHER -> AppIcons.File
}

fun MediaDevice.icon(): ImageVector = if (isServer) AppIcons.Server else AppIcons.Tv

fun MediaDevice.subtitle(): String = listOf(manufacturer, host).filter { it.isNotBlank() }.joinToString(" · ")

/** `M:SS` / `H:MM:SS` for UI. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

actual val deviceLabel: String = when {
    System.getProperty("os.name").orEmpty().startsWith("Mac") -> "Mac"
    System.getProperty("os.name").orEmpty().startsWith("Windows") -> "PC"
    else -> "Computer"
}

@Composable
actual fun rememberMediaPermissionRequest(): MediaPermissionRequest = remember { MediaPermissionRequest { it(true) } }

actual val supportsAirPlay: Boolean = false

@Composable
actual fun AirPlayButton(modifier: Modifier, tint: Color?) {}

package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable

/** AVPlayerViewController has its own full-screen mode; the app only hides its chrome. */
@Composable
actual fun SystemFullscreenEffect(fullscreen: Boolean, onExit: () -> Unit) = Unit

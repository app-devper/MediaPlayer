package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Asks for media read permission where needed (Android 13+ READ_MEDIA_*, iOS/desktop: granted). */
fun interface MediaPermissionRequest {
    fun request(onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberMediaPermissionRequest(): MediaPermissionRequest

/** Native video/audio player with its own transport controls. */
@Composable
expect fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
)

/** True where the OS offers AirPlay routing (iOS). */
expect val supportsAirPlay: Boolean

/** System AirPlay route picker button (AVRoutePickerView on iOS; nothing elsewhere). */
@Composable
expect fun AirPlayButton(modifier: Modifier = Modifier)

/** "Android" / "iOS" / OS name — shown for the local playback target. */
expect val deviceLabel: String

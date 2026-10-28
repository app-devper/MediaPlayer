package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appdevper.mediaplayer.airplay.AirPlayPlayer

/** Asks for media read permission where needed (Android 13+ READ_MEDIA_*, iOS/desktop: granted). */
fun interface MediaPermissionRequest {
    fun request(onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberMediaPermissionRequest(): MediaPermissionRequest

/**
 * Shows the app's local player ([com.appdevper.mediaplayer.domain.session.LocalPlayer]) and its
 * transport controls: the picture for a video, the controls for a song. The player itself follows
 * the playback session and plays on when this leaves the screen.
 */
@Composable
expect fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    modifier: Modifier = Modifier,
    /** Shows a full-screen button (desktop also: double-click) when set; iOS uses AVKit's own. */
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
)

/** True where the OS offers AirPlay routing (iOS). */
expect val supportsAirPlay: Boolean

/** System AirPlay route picker button (AVRoutePickerView on iOS; nothing elsewhere). */
@Composable
expect fun AirPlayButton(modifier: Modifier = Modifier)

/** Draws AirPlay screen mirroring from [player] (Android: a SurfaceView; desktop: decoded frames). */
@Composable
expect fun AirPlayMirrorView(player: AirPlayPlayer, modifier: Modifier = Modifier)

/**
 * Puts the platform in or out of full screen (desktop: the window; Android: system bars and
 * landscape). Calls [onExit] when the user leaves it the platform's way (Esc, Back).
 */
@Composable
expect fun SystemFullscreenEffect(fullscreen: Boolean, onExit: () -> Unit)

/** "Android" / "iOS" / OS name — shown for the local playback target. */
expect val deviceLabel: String

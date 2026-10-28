package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.appdevper.mediaplayer.airplay.AirPlayPlayer

/** Asks for media read permission where needed (Android 13+ READ_MEDIA_*, iOS/desktop: granted). */
fun interface MediaPermissionRequest {
    fun request(onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberMediaPermissionRequest(): MediaPermissionRequest

/**
 * The picture of the app's local player ([com.appdevper.mediaplayer.ui.player.LocalPlayerView] puts
 * the controls over it): Android's PlayerView, desktop's FFmpeg frames, iOS's AVPlayerViewController
 * (with AVKit's own controls, full screen, Picture in Picture and AirPlay; see [videoSurfaceHasControls]).
 */
@Composable
expect fun VideoSurface(title: String, modifier: Modifier = Modifier)

/** [VideoSurface] brings its own controls (AVKit on iOS), so ours don't go over it. */
expect val videoSurfaceHasControls: Boolean

/**
 * A mouse and keyboard (desktop): a click on the video plays or pauses, the pointer hides with the
 * controls, keys are shortcuts and the bar has a volume control. Otherwise touch (and TV remotes).
 */
expect val pointerIsMouse: Boolean

/** Hides the mouse pointer over this element while [hidden] (desktop); nothing elsewhere. */
expect fun Modifier.hidePointer(hidden: Boolean): Modifier

/** True where the OS offers AirPlay routing (iOS). */
expect val supportsAirPlay: Boolean

/** System AirPlay route picker button (AVRoutePickerView on iOS; nothing elsewhere); [tint] overrides its idle color. */
@Composable
expect fun AirPlayButton(modifier: Modifier = Modifier, tint: Color? = null)

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

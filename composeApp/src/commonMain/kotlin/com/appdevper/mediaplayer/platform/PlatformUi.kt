package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.domain.model.LocalPlayerCommand
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Asks for media read permission where needed (Android 13+ READ_MEDIA_*, iOS/desktop: granted). */
fun interface MediaPermissionRequest {
    fun request(onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberMediaPermissionRequest(): MediaPermissionRequest

/**
 * Native video/audio player with its own transport controls. It can also be driven from outside
 * ([commands], [volume]) and reports what it is doing through [onStatus] about twice a second.
 * With [autoPlay] false it loads paused and reports [TransportState.STOPPED] until it plays.
 */
@Composable
expect fun NativeMediaPlayer(
    url: String,
    title: String,
    isAudio: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    /** 0..1; 0 when muted. */
    volume: Float = 1f,
    commands: Flow<LocalPlayerCommand> = emptyFlow(),
    onStatus: (RendererStatus) -> Unit = {},
)

/** True where the OS offers AirPlay routing (iOS). */
expect val supportsAirPlay: Boolean

/** System AirPlay route picker button (AVRoutePickerView on iOS; nothing elsewhere). */
@Composable
expect fun AirPlayButton(modifier: Modifier = Modifier)

/** Draws AirPlay screen mirroring from [player] (Android: a SurfaceView; desktop: decoded frames). */
@Composable
expect fun AirPlayMirrorView(player: AirPlayPlayer, modifier: Modifier = Modifier)

/** "Android" / "iOS" / OS name — shown for the local playback target. */
expect val deviceLabel: String

/** How often the native players report position. */
internal const val STATUS_INTERVAL_MS = 500L

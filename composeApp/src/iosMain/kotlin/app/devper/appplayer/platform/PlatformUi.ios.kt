@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package app.devper.appplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import app.devper.appplayer.designsystem.theme.AppTheme
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor

actual val deviceLabel: String = "iPhone / iPad"

@Composable
actual fun rememberMediaPermissionRequest(): MediaPermissionRequest = remember { MediaPermissionRequest { it(true) } }

actual val supportsAirPlay: Boolean = true

/**
 * System AirPlay picker: routes audio/video to Apple TV, AirPlay speakers and AirPlay 2 TVs. Drawn in
 * the app's colors (not the system's, which can differ from the app's theme) on a clear background;
 * [tint] overrides the idle color (e.g. on the song page's bar).
 */
@Composable
actual fun AirPlayButton(modifier: Modifier, tint: Color?) {
    val c = AppTheme.colors
    val idle = (tint ?: c.textSecondary).toUIColor()
    val active = c.success.toUIColor()
    UIKitView(
        factory = {
            AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 40.0, 40.0)).apply {
                prioritizesVideoDevices = true
                backgroundColor = UIColor.clearColor
                opaque = false
            }
        },
        // Follows theme changes; "active" (something is playing over AirPlay) uses the app's green.
        update = {
            it.tintColor = idle
            it.activeTintColor = active
        },
        modifier = modifier.semantics { contentDescription = "AirPlay" },
        // Over the Compose canvas, not under it: underneath, its clear background showed the black
        // window behind the app instead of the bar it sits on.
        properties = UIKitInteropProperties(placedAsOverlay = true),
    )
}

private fun Color.toUIColor(): UIColor =
    UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

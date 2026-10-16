@file:OptIn(ExperimentalForeignApi::class)

package com.appdevper.mediaplayer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.labelColor
import platform.UIKit.secondaryLabelColor

actual val deviceLabel: String = "iPhone / iPad"

@Composable
actual fun rememberMediaPermissionRequest(): MediaPermissionRequest = remember { MediaPermissionRequest { it(true) } }

actual val supportsAirPlay: Boolean = true

/** System AirPlay picker: routes audio/video to Apple TV, AirPlay speakers and AirPlay 2 TVs. */
@Composable
actual fun AirPlayButton(modifier: Modifier) {
    UIKitView(
        factory = {
            AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 40.0, 40.0)).apply {
                prioritizesVideoDevices = true
                tintColor = UIColor.secondaryLabelColor
                activeTintColor = UIColor.labelColor
            }
        },
        modifier = modifier,
    )
}

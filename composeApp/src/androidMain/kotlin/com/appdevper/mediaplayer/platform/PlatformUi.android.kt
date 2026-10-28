package com.appdevper.mediaplayer.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

actual val deviceLabel: String = "Android"

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@Composable
actual fun rememberMediaPermissionRequest(): MediaPermissionRequest {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.any { it }
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(launcher) {
        MediaPermissionRequest { onResult ->
            val perms = mediaPermissions()
            val missing = perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.none { it != Manifest.permission.POST_NOTIFICATIONS }) {
                onResult(true)
            } else {
                pending[0] = onResult
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}

actual val supportsAirPlay: Boolean = false

@Composable
actual fun AirPlayButton(modifier: Modifier, tint: Color?) {}

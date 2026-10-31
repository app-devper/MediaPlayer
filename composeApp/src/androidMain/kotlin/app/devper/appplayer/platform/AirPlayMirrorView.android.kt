package app.devper.appplayer.platform

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.devper.appplayer.airplay.AirPlayPlayer

@Composable
actual fun AirPlayMirrorView(player: AirPlayPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = player.setSurface(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = player.setSurface(null)
                })
            }
        },
        modifier = modifier,
    )
}

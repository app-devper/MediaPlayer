package app.devper.appplayer.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.devper.appplayer.airplay.AirPlayPlayer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

@Composable
actual fun AirPlayMirrorView(player: AirPlayPlayer, modifier: Modifier) {
    val frame by player.frames.collectAsStateWithLifecycle()
    val f = frame ?: return Box(modifier)
    val bitmap = remember(f) {
        SkiaImage.makeRaster(ImageInfo(f.width, f.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bgra, f.width * 4)
            .toComposeImageBitmap()
    }
    Image(bitmap, contentDescription = "Mirrored screen", modifier = modifier, contentScale = ContentScale.Fit)
}

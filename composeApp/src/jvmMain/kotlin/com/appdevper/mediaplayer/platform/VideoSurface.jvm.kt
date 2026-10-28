package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appdevper.mediaplayer.playback.FfmpegPlaybackEngine
import com.appdevper.mediaplayer.playback.FfmpegPlayer
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.koin.compose.koinInject

/** The frames of the app's FFmpeg player ([FfmpegPlaybackEngine]); empty while nothing is loaded. */
@Composable
actual fun VideoSurface(title: String, modifier: Modifier) {
    val player = koinInject<FfmpegPlaybackEngine>().player.collectAsStateWithLifecycle().value ?: return
    Frames(player, title, modifier)
}

@Composable
private fun Frames(player: FfmpegPlayer, title: String, modifier: Modifier) {
    val frame by player.frames.collectAsStateWithLifecycle()
    val f = frame ?: return
    val bitmap = remember(f) {
        SkiaImage.makeRaster(ImageInfo(f.width, f.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bgra, f.width * 4)
            .toComposeImageBitmap()
    }
    Image(bitmap, contentDescription = title, modifier = modifier, contentScale = ContentScale.Fit)
}

actual val videoSurfaceHasControls: Boolean = false

actual val pointerIsMouse: Boolean = true

actual fun Modifier.hidePointer(hidden: Boolean): Modifier = pointerHoverIcon(if (hidden) HiddenPointer else PointerIcon.Default)

/** An empty cursor: AWT has no "none", so a 1×1 transparent image. Made when first needed (not headless). */
private val HiddenPointer: PointerIcon by lazy {
    if (GraphicsEnvironment.isHeadless()) {
        PointerIcon.Default
    } else {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
    }
}

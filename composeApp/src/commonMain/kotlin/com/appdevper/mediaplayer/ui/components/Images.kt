package com.appdevper.mediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.appdevper.mediaplayer.ui.theme.AppTheme
import com.appdevper.mediaplayer.ui.theme.Radius

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, shape: Shape = Radius.medium, tint: Color = AppTheme.colors.textPrimary) {
    Box(
        modifier.size(size).clip(shape).background(AppTheme.colors.surfaceSecondary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size((size * 0.5f).coerceAtMost(64.dp)), tint = tint)
    }
}

/** Remote image with a monochrome icon placeholder while loading / on error. */
@Composable
fun Thumbnail(url: String?, fallback: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, shape: Shape = Radius.medium) {
    if (url == null) {
        IconTile(fallback, modifier, size, shape)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape).background(AppTheme.colors.surfaceSecondary),
        loading = { IconTile(fallback, size = size, shape = shape, tint = AppTheme.colors.textTertiary) },
        error = { IconTile(fallback, size = size, shape = shape) },
    )
}

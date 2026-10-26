package com.appdevper.mediaplayer.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius

/** Background highlight on hover/selection instead of Material ripples. */
@Composable
fun Modifier.interactive(
    onClick: (() -> Unit)?,
    selected: Boolean = false,
    shape: Shape = Radius.medium,
    role: Role = Role.Button,
    /** false: shown and announced as disabled (with [onClick] set), and not clickable. */
    enabled: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val c = AppTheme.colors
    val bg = when {
        selected -> c.selected
        hovered && onClick != null && enabled -> c.hover
        else -> Color.Transparent
    }
    return this
        .clip(shape)
        .background(bg)
        .hoverable(source)
        .then(if (onClick != null) Modifier.clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick) else Modifier)
}

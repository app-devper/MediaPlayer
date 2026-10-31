package app.devper.appplayer.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.devper.appplayer.designsystem.theme.AppTheme
import app.devper.appplayer.designsystem.theme.Radius

@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = AppTheme.colors.textSecondary,
) {
    Box(
        // Touch target ≥ 48dp (44pt) on touch screens even when the button looks smaller.
        modifier.minimumInteractiveComponentSize().size(size)
            .interactive(onClick, shape = Radius.small, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

enum class PillStyle { Primary, Secondary, Ghost }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = AppTheme.colors
    val (bg, fg) = when (style) {
        PillStyle.Primary -> c.primary to c.onPrimary
        PillStyle.Secondary -> c.background to c.textPrimary
        PillStyle.Ghost -> Color.Transparent to c.textPrimary
    }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(Radius.pill)
            .background(if (hovered && style != PillStyle.Primary) c.hover else bg.copy(alpha = if (hovered) 0.85f else 1f))
            .then(if (style == PillStyle.Secondary) Modifier.border(1.dp, c.border, Radius.pill) else Modifier)
            .hoverable(source)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (enabled) fg else fg.copy(alpha = 0.4f)
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** Round black/white transport button (play/pause). */
@Composable
fun CircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, size: Dp = 56.dp, enabled: Boolean = true) {
    val c = AppTheme.colors
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) c.primary else c.primary.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(size * 0.42f), tint = c.onPrimary)
    }
}

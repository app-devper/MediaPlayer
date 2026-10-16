package com.appdevper.mediaplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.ui.icons.AppIcons
import com.appdevper.mediaplayer.ui.theme.AppTheme
import com.appdevper.mediaplayer.ui.theme.Radius

/** Content column centred with a max width, like ChatGPT's conversation column. */
@Composable
fun CenteredColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 768.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

/** Background highlight on hover/selection instead of Material ripples. */
@Composable
fun Modifier.interactive(
    onClick: (() -> Unit)?,
    selected: Boolean = false,
    shape: Shape = Radius.medium,
    role: Role = Role.Button,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val c = AppTheme.colors
    val bg = when {
        selected -> c.selected
        hovered && onClick != null -> c.hover
        else -> Color.Transparent
    }
    return this
        .clip(shape)
        .background(bg)
        .hoverable(source)
        .then(if (onClick != null) Modifier.clickable(source, indication = null, role = role, onClick = onClick) else Modifier)
}

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
        modifier.size(size).interactive(if (enabled) onClick else null, shape = Radius.small),
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

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
) {
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().interactive(onClick, selected).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.textTertiary,
        modifier = modifier.padding(start = 10.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(if (active) AppTheme.colors.success else AppTheme.colors.textTertiary))
}

@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = AppTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.primary,
            checkedThumbColor = c.onPrimary,
            checkedBorderColor = c.primary,
            uncheckedTrackColor = if (c.isDark) Color(0xFF4A4A4A) else Color(0xFFE3E3E3),
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Rounded "composer" input — the ChatGPT message box, used here for searching. */
@Composable
fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = AppIcons.Search,
) {
    val c = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Radius.xLarge)
            .background(c.surfaceSecondary)
            .border(BorderStroke(1.dp, c.border), Radius.xLarge)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(leadingIcon, null, Modifier.size(20.dp), tint = c.textTertiary)
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.textPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) GhostIconButton(AppIcons.Close, "Clear", onClick = { onValueChange("") }, size = 28.dp, iconSize = 16.dp)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = AppTheme.colors
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(icon, size = 52.dp, shape = Radius.large, tint = c.textSecondary)
            Spacer(Modifier.size(2.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
            if (action != null) {
                Spacer(Modifier.size(6.dp))
                action()
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = AppTheme.colors.textSecondary)
    }
}

/** Bordered card used for suggestions on the home screen. */
@Composable
fun OutlineCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = AppTheme.colors
    Box(
        modifier
            .border(1.dp, c.border, Radius.large)
            .interactive(onClick, shape = Radius.large)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun ProvideContentColor(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)

fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.FOLDER -> AppIcons.Folder
    MediaKind.VIDEO -> AppIcons.Film
    MediaKind.AUDIO -> AppIcons.Music
    MediaKind.IMAGE -> AppIcons.Image
    MediaKind.OTHER -> AppIcons.File
}

fun MediaDevice.icon(): ImageVector = if (isServer) AppIcons.Server else AppIcons.Tv

fun MediaDevice.subtitle(): String = listOf(manufacturer, host).filter { it.isNotBlank() }.joinToString(" · ")

/** Thin 4dp slider with a small round thumb (Material's default is much heavier). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    val c = AppTheme.colors
    val colors = SliderDefaults.colors(
        thumbColor = c.primary,
        activeTrackColor = c.primary,
        inactiveTrackColor = if (c.isDark) Color(0xFF4A4A4A) else Color(0xFFE3E3E3),
        disabledActiveTrackColor = c.textTertiary,
        disabledInactiveTrackColor = c.selected,
    )
    val source = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = source,
        thumb = {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (enabled) c.primary else c.textTertiary))
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                colors = colors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** `M:SS` / `H:MM:SS` for UI. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

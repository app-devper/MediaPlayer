package com.appdevper.mediaplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.ui.theme.AppTheme

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

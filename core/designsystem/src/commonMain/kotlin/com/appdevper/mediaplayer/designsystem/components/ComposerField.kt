package com.appdevper.mediaplayer.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius

/**
 * Rounded "composer" input — the ChatGPT message box, used here for searching. [label] names the field
 * for screen readers (show it on screen too; the placeholder isn't a label); [keyboardOptions] and
 * [keyboardActions] pick the keyboard and what its action key does.
 */
@Composable
fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = AppIcons.Search,
    label: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
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
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth().then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            )
        }
        if (value.isNotEmpty()) GhostIconButton(AppIcons.Close, "Clear", onClick = { onValueChange("") }, size = 28.dp, iconSize = 16.dp)
    }
}

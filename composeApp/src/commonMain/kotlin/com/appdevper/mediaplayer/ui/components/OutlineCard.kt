package com.appdevper.mediaplayer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdevper.mediaplayer.ui.theme.AppTheme
import com.appdevper.mediaplayer.ui.theme.Radius

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

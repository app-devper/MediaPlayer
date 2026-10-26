package com.appdevper.mediaplayer.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appdevper.mediaplayer.designsystem.components.IconTile
import com.appdevper.mediaplayer.designsystem.icons.AppIcons
import com.appdevper.mediaplayer.designsystem.theme.AppTheme
import com.appdevper.mediaplayer.designsystem.theme.Radius

/** AirPlay pairing code to type on the sender, shown over everything until pairing ends. */
@Composable
internal fun PinOverlay(pin: String, client: String?) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 360.dp)
                .background(c.surface, Radius.xLarge).border(1.dp, c.border, Radius.xLarge)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(AppIcons.Cast, size = 48.dp, shape = Radius.pill)
            Text("AirPlay code", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(
                pin,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 44.sp, letterSpacing = 12.sp, fontWeight = FontWeight.SemiBold),
                color = c.textPrimary,
            )
            Text(
                "Enter this code on ${client ?: "your device"} to connect. It's only asked the first time.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

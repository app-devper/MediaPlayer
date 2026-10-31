package app.devper.appplayer.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.devper.appplayer.designsystem.theme.AppTheme
import app.devper.appplayer.designsystem.theme.Radius

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

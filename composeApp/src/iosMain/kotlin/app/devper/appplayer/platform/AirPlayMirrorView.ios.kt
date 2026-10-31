package app.devper.appplayer.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.devper.appplayer.airplay.AirPlayPlayer

/** Never shown: iOS can't receive AirPlay. */
@Composable
actual fun AirPlayMirrorView(player: AirPlayPlayer, modifier: Modifier) = Box(modifier)

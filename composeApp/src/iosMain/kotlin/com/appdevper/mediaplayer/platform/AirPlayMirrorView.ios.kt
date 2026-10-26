package com.appdevper.mediaplayer.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appdevper.mediaplayer.airplay.AirPlayPlayer

/** Never shown: iOS can't receive AirPlay. */
@Composable
actual fun AirPlayMirrorView(player: AirPlayPlayer, modifier: Modifier) = Box(modifier)

package com.appdevper.mediaplayer

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.appdevper.mediaplayer.di.initKoin
import com.appdevper.mediaplayer.domain.usecase.StopMediaServerUseCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.koin.mp.KoinPlatform

fun main() {
    initKoin()
    application {
        Window(
            onCloseRequest = {
                // Send ssdp:byebye so TVs drop our server immediately.
                runBlocking { KoinPlatform.getKoin().get<StopMediaServerUseCase>()() }
                exitApplication()
            },
            title = "Media Player",
            icon = remember { appIcon() },
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            App()
        }
    }
}

private fun appIcon(): BitmapPainter? = object {}.javaClass.getResourceAsStream("/icon.png")?.use {
    BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
}

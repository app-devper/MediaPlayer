package com.appdevper.mediaplayer.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Outline icon set (24px grid, 1.75px stroke, round caps/joins) in the thin-line style used by
 * ChatGPT. Geometry follows Lucide (ISC licence). Filled variants are used for the main
 * transport buttons; [Logo] is the app mark.
 */
object AppIcons {
    private const val STROKE = 1.75f

    private fun outline(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) {
                addPath(
                    pathData = addPathNodes(p),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (p in paths) addPath(pathData = addPathNodes(p), fill = SolidColor(Color.Black))
        }.build()

    // region navigation / chrome
    val Back = outline("back", "m12 19-7-7 7-7", "M19 12H5")
    val ChevronDown = outline("chevron_down", "m6 9 6 6 6-6")
    val ChevronRight = outline("chevron_right", "m9 18 6-6-6-6")
    val Close = outline("close", "M18 6 6 18", "m6 6 12 12")
    val Sidebar = outline("sidebar", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 3v18")
    val Menu = outline("menu", "M4 7h16", "M4 12h16", "M4 17h10")
    val Refresh = outline(
        "refresh",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val Search = outline("search", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "m21 21-4.3-4.3")
    val Home = outline("home", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Check = outline("check", "M20 6 9 17l-5-5")
    val Plus = outline("plus", "M5 12h14", "M12 5v14")
    val Trash = outline("trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    val Info = outline("info", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 16v-4", "M12 8h.01")
    val Settings = outline(
        "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    )
    // endregion

    // region media
    val Folder = outline("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    val Film = outline(
        "film",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4",
    )
    val Music = outline("music", "M9 18V5l12-2v13", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M15 16a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
    val Image = outline(
        "image",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M7 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
    )
    val File = outline("file", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4")
    val ListMusic = outline("list_music", "M21 15V6", "M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z", "M12 12H3", "M16 6H3", "M12 18H3")
    val Volume = outline(
        "volume",
        "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728",
    )
    // endregion

    // region devices
    val Server = outline(
        "server",
        "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
        "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
        "M6 6h.01", "M6 18h.01",
    )
    val Tv = outline("tv", "M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "m17 2-5 5-5-5")
    val Phone = outline("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01")
    val Laptop = outline("laptop", "M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16")
    val Cast = outline("cast", "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a9 9 0 0 1 8 8", "M2 16a5 5 0 0 1 4 4", "M2 20h.01")
    val AirPlay = outline("airplay", "M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1", "m12 15 5 6H7Z")
    val Radio = outline(
        "radio",
        "M4.9 19.1C1 15.2 1 8.8 4.9 4.9", "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5",
        "M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5", "M19.1 4.9C23 8.8 23 15.1 19.1 19",
    )
    val Share = outline(
        "share",
        "M15 5a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M3 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M15 19a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "m8.59 13.51 6.83 3.98", "m15.41 6.51-6.82 3.98",
    )
    // endregion

    // region transport (filled, for primary buttons)
    val Play = filled("play", "M7 4.8v14.4c0 .8.9 1.3 1.6.85l11.2-7.2c.6-.4.6-1.3 0-1.7L8.6 3.95C7.9 3.5 7 4 7 4.8z")
    val Pause = filled("pause", "M7 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M14 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Stop = filled("stop", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val SkipNext = filled("skip_next", "M4 5.6v12.8c0 .8.9 1.3 1.6.8L14.4 13c.6-.4.6-1.3 0-1.7L5.6 4.8C4.9 4.3 4 4.8 4 5.6z", "M18 4a1 1 0 0 1 1 1v14a1 1 0 0 1-2 0V5a1 1 0 0 1 1-1z")
    val SkipPrevious = filled("skip_prev", "M20 5.6v12.8c0 .8-.9 1.3-1.6.8L9.6 13c-.6-.4-.6-1.3 0-1.7l8.8-6.5c.7-.5 1.6 0 1.6.8z", "M6 4a1 1 0 0 1 1 1v14a1 1 0 0 1-2 0V5a1 1 0 0 1 1-1z")
    // endregion

    /** App mark: a play button between broadcast waves — "play anything, anywhere on the network". */
    val Logo: ImageVector = ImageVector.Builder("logo", 24.dp, 24.dp, 24f, 24f).apply {
        for (p in listOf("M4.9 4.9a10 10 0 0 0 0 14.2", "M19.1 4.9a10 10 0 0 1 0 14.2", "M7.8 7.8a6 6 0 0 0 0 8.4", "M16.2 7.8a6 6 0 0 1 0 8.4")) {
            addPath(addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        }
        addPath(addPathNodes("M10.4 9.3v5.4c0 .55.6.88 1.06.58l4.1-2.7c.42-.27.42-.89 0-1.16l-4.1-2.7c-.46-.3-1.06.03-1.06.58z"), fill = SolidColor(Color.Black))
    }.build()
}

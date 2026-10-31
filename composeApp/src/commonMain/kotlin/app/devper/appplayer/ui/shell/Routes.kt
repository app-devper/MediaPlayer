package app.devper.appplayer.ui.shell

import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class BrowseRoute(val deviceId: String, val folderId: String, val title: String)

@Serializable
object PlayerRoute

@Serializable
object ServerRoute

@Serializable
object MirrorRoute

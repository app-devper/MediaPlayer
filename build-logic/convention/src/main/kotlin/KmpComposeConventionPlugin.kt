import org.gradle.api.Plugin
import org.gradle.api.Project

/** Compose Multiplatform on top of `mediaplayer.kmp.library` or `mediaplayer.kmp.application`. */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    }
}

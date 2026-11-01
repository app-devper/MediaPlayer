import app.devper.appplayer.buildlogic.configureAndroidLibrary
import app.devper.appplayer.buildlogic.configureKotlinMultiplatform
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A KMP library module targeting Android, Desktop (JVM) and iOS. The Android target comes from
 * AGP's KMP library plugin (one variant, no build types); the module sets
 * `kotlin { android { namespace = ... } }` itself.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        configureKotlinMultiplatform()
        configureAndroidLibrary()
    }
}

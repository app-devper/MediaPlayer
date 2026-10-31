import com.android.build.api.dsl.LibraryExtension
import app.devper.appplayer.buildlogic.configureAndroid
import app.devper.appplayer.buildlogic.configureKotlinMultiplatform
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** A KMP library module targeting Android, Desktop (JVM) and iOS. Set `android.namespace` in the module. */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.library")

        configureKotlinMultiplatform()
        extensions.configure<LibraryExtension> { configureAndroid(this) }
    }
}

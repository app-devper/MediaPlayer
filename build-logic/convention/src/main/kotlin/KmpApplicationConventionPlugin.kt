import com.android.build.api.dsl.ApplicationExtension
import com.appdevper.mediaplayer.buildlogic.configureAndroid
import com.appdevper.mediaplayer.buildlogic.configureKotlinMultiplatform
import com.appdevper.mediaplayer.buildlogic.libs
import com.appdevper.mediaplayer.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The app module: same targets as the libraries, plus Android app versioning from the catalog. */
class KmpApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.application")

        configureKotlinMultiplatform()
        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig {
                targetSdk = libs.version("android-targetSdk").toInt()
                versionCode = libs.version("app-versionCode").toInt()
                versionName = libs.version("app-version")
            }
        }
    }
}

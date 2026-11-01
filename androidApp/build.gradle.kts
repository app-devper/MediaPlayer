import java.util.Properties

// The installable Android app: a thin shell around :composeApp's shared UI plus the Android
// services it declares. AGP 9 compiles the Kotlin here itself (built-in Kotlin); the Compose
// compiler plugin still comes from Kotlin.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.devper.appplayer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.devper.appplayer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.app.versionCode.get().toInt()
        versionName = libs.versions.app.version.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
        }
    }

    // The release key lives outside the repo (dev-flow stage 8.7). The properties file is named by
    // APPPLAYER_KEYSTORE_PROPERTIES, else ~/.devper/keystores/appplayer-release.properties. Without
    // it the release build is unsigned: it still builds, it just cannot be installed or distributed.
    val keystoreProperties = releaseKeystoreProperties()
    if (keystoreProperties != null) {
        signingConfigs.create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
}

fun releaseKeystoreProperties(): Properties? {
    val path = System.getenv("APPPLAYER_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.devper/keystores/appplayer-release.properties"
    val file = File(path)
    if (!file.isFile) {
        logger.lifecycle("Android release signing: no keystore properties at $path, the release APK will be unsigned")
        return null
    }
    return Properties().apply { file.inputStream().use { load(it) } }
}

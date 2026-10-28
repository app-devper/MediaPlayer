plugins {
    alias(libs.plugins.mediaplayer.kmp.library)
}

// Test fakes for the domain's interfaces, shared by every module's tests. Never a main dependency.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
        }
    }
}

android {
    namespace = "com.appdevper.mediaplayer.testing"
}

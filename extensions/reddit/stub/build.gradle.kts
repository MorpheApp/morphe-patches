plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.morphe.extension"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }
}

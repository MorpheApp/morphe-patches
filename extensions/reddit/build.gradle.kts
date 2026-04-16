dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:reddit:stub"))
    compileOnly(libs.morphe.extensions.library)

    // Used by MorpheSettingsIconVectorDrawable.
    implementation(libs.androidx.core)

    // Used by SpoofSignaturePatch.
    implementation(libs.hiddenapi)
}

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}

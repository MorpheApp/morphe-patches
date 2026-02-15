dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:reddit:stub"))

    // Used by MorpheSettingsIconVectorDrawable.
    implementation(libs.androidx.core)

    // Used by SpoofSignaturePatch.
    implementation(libs.hiddenapi)
}

android {
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }
}

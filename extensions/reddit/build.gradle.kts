import com.android.build.api.dsl.ApplicationExtension

dependencies {
    // Parse archived Markdown; Reddit's own rich-text renderer still draws the result.
    implementation("org.commonmark:commonmark:0.32.0")
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:reddit:stub"))
    compileOnly(libs.morphe.extensions.library)

    // Used by MorpheSettingsIconVectorDrawable.
    implementation(libs.androidx.core)

    // Used by SpoofSignaturePatch.
    implementation(libs.hiddenapi)
}

configure<ApplicationExtension> {
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}

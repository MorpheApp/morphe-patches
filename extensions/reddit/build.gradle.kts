import com.android.build.api.dsl.ApplicationExtension

dependencies {
    // The host supplies OkHttp. Never bundle another copy into the patched application.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
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

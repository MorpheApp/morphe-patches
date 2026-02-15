dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:reddit:stub"))
    implementation(libs.androidx.core)
    implementation(libs.hiddenapi)
}

android {
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }
}

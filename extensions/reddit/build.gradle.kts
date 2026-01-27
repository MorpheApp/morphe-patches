dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:reddit:stub"))
    compileOnly(libs.annotation)
    implementation(libs.hiddenapi)
}

android {
    defaultConfig {
        minSdk = 28
    }
}

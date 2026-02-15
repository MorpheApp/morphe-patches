dependencies {
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(libs.annotation)
}

android {
    defaultConfig {
        minSdk = 26
    }
}

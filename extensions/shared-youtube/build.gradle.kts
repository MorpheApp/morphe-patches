dependencies {
    implementation(project(":extensions:shared-youtube:library"))
}

android {
    namespace = "app.morphe.extension"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

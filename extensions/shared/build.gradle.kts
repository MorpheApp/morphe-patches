import com.android.build.api.dsl.ApplicationExtension

dependencies {
    implementation(project(":extensions:shared:library"))
}

configure<ApplicationExtension> {
    namespace = "app.morphe.extension"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

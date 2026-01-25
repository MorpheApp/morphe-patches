import java.lang.Boolean.TRUE

extension {
    name = "extensions/all/connectivity/wifi/spoof/spoof-wifi.rve"
}

android {
    namespace = "app.morphe.extension"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        release {
            isMinifyEnabled = TRUE
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.annotation)
}

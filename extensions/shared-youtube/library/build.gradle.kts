plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.protobuf)
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

dependencies {
    implementation(libs.androidx.javascriptengine)
    implementation(libs.gson)
    implementation(libs.protobuf.javalite)
    implementation(project(":extensions:shared:library"))
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
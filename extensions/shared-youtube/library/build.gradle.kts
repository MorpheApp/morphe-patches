import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.protobuf)
}

configure<LibraryExtension> {
    namespace = "app.morphe.extension.shared.youtube"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.morphe.extensions.library)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.gson)
    implementation(libs.collections4)
    implementation(libs.protobuf.javalite)
    implementation(project(":extensions:shared:library"))
    compileOnly(project(":extensions:shared-youtube:stub"))
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

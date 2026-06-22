import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    compileOnly(libs.annotation)
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))

    implementation(libs.collections4)
    implementation(libs.lang3)
    implementation(libs.protobuf.javalite)
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }
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


plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.annotation)

    implementation(libs.protobuf.javalite)
}

android {
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


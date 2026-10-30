import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.morphe.extensions.library)

    compileOnly(libs.annotation)
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared-youtube:stub"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))

    implementation(libs.collections4)
    implementation(libs.protobuf.javalite)
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }

    // Logger calls android.util.Log, which throws in local unit tests unless stubbed.
    testOptions.unitTests.isReturnDefaultValues = true
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

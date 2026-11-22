@file:Suppress("AvoidDuplicateDependencies")

import com.android.build.api.dsl.ApplicationExtension

dependencies {
    implementation(libs.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kxml2)
    testImplementation(project(":extensions:shared:library"))
    testImplementation(libs.morphe.extensions.library)
    testImplementation(project(":extensions:shared-youtube:library"))
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared-youtube:stub"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.annotation)
    compileOnly(libs.protobuf.javalite)
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

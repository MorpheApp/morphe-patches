import com.android.build.api.dsl.ApplicationExtension

extension {
    name = "extensions/newpipe/newpipe.mpe"
}

configure<ApplicationExtension> {
    namespace = "app.morphe.extension.newpipe"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes.getByName("release") {
        proguardFile("newpipe-proguard-rules.pro")
    }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:2f7d9da4ce82248637fdd3f8025086bd345f7fc2") {
        // The shared YouTube extension already supplies this library to the patched app.
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    compileOnly(libs.protobuf.javalite)
    compileOnly(libs.annotation)
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:shared-youtube:library"))
    testImplementation(libs.junit)
}

import com.android.build.api.dsl.ApplicationExtension

dependencies {
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.annotation)
    compileOnly(libs.gson)
    
    // For Listen Together
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly(libs.protobuf.javalite)
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }
}


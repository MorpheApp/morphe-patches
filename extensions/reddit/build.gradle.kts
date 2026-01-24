dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:reddit:stub"))
    compileOnly(libs.annotation)
    implementation(libs.hiddenapi)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

group = "app.morphe"

patches {
    about {
        name = "Morphe Patches"
        description = "Patches for Morphe"
        source = "git@github.com:MorpheApp/morphe-patches.git"
        author = "MorpheApp"
        contact = "na@na"
        website = "na"
        license = "GNU General Public License v3.0, with project attributation required"
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

tasks {
    // TODO: Fix AddResourcesPatch so strings never need pre-processing.
    register<JavaExec>("preprocessCrowdinStrings") {
        description = "Preprocess strings for Crowdin push"

        dependsOn(compileKotlin)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.CrowdinPreprocessorKt")

        args = listOf(
            "src/main/resources/addresources/values/strings.xml",
            // Ideally this would use build/tmp/crowdin/strings.xml
            // But using that does not work with Crowdin pull because
            // it does not recognize the strings.xml file belongs to this project.
            "src/main/resources/addresources/values/strings.xml"
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-receivers")
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-patches")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
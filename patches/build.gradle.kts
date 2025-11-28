group = "app.revanced"

patches {
    about {
        name = "ReVanced Liso Patches"
        description = "Forked patches, from the former ReVanced team member and 3 year contributor to ReVanced YouTube"
        source = "git@github.com:LisoUseInAIKyrios/revanced-patches.git"
        author = "LisoUseInAIKyrios"
        contact = "na@na"
        website = "na"
        license = "GNU General Public License v3.0"
    }
}

repositories {
    mavenLocal()
    flatDir { // Use custom forked patcher libraries.
        dirs("libs")
    }
    gradlePluginPortal()
    google()

    // Use same copy pasted hack fix used by CLI. Without this it won't resolve patcher dependencies.
    maven {
        url = uri("https://maven.pkg.github.com/revanced/registry")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.apksig)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

tasks {
    register<JavaExec>("preprocessCrowdinStrings") {
        description = "Preprocess strings for Crowdin push"

        dependsOn(compileKotlin)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.revanced.util.CrowdinPreprocessorKt")

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
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
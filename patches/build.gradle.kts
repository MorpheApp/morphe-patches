import org.gradle.api.tasks.JavaExec

group = "app.morphe"

patches {
    about {
        name = "Morphe Patches"
        description = "Patches for Morphe"
        source = "git@github.com:MorpheApp/morphe-patches.git"
        author = "MorpheApp"
        contact = "na"
        website = "https://morphe.software"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")
val jamApk = providers.gradleProperty("jamApk")
val jamOutput = providers.gradleProperty("jamOutput")

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    patchListGeneratorClasspath(libs.gson)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

tasks {
    test {
        useJUnitPlatform()
        jamApk.orNull?.let { systemProperty("jamApk", it) }
    }

    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.patches.util.resource.CheckStringResourcesKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }

    register<JavaExec>("validateJam") {
        description = "Patches a supplied YouTube Music APK for Jam validation and optional device testing"
        group = "verification"

        dependsOn("testClasses")
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.morphe.patches.music.interaction.jam.JamDeviceBuildKt")

        doFirst {
            val apk = jamApk.orNull
                ?: throw GradleException("validateJam requires -PjamApk=/absolute/path/to/ytm.apk")
            setArgs(listOfNotNull(apk, jamOutput.orNull))
        }
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

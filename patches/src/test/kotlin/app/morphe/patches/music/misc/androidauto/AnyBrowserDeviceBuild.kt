/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4931
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

/**
 * APK validation entry point for the Gradle `:patches:validateAnyBrowser` task.
 *
 * Set ANDROID_HOME (or ANDROID_SDK_ROOT) to an Android SDK with build tools, then run: `./gradlew
 * :patches:validateAnyBrowser -PanyApk=/absolute/path/music.apk` Add
 * `-PanyOutput=/absolute/path/any-unsigned.apk` to assemble an unsigned device APK. Sign that APK
 * before installing it. It uses the isolated package `app.morphe.any.next.music`, so it can coexist
 * with a normal installation.
 *
 * The task applies Android Auto (Any) Music Browser Support together with GmsCore support,
 * including the Android Auto patch it depends on. Patch failures and SDK DEX/hierarchy
 * verification failures abort the build. Without anyOutput it validates without assembling.
 *
 * Successful patching and verification do not establish device behavior.
 */
package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.all.misc.clone.cloneAppPatch
import app.morphe.patches.music.misc.gms.gmsCoreSupportPatch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

private const val PROBE_PACKAGE = "app.morphe.any.next.music"

/** Lets the probe install as the base APK alone, without the splits of the original bundle. */
private val installableWithoutSplits = resourcePatch {
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.root.removeAttribute("android:isSplitRequired")
            manifest.root.removeAttribute("android:requiredSplitTypes")
            val application = manifest.elements("application").first()
            application.elements("meta-data").filter {
                it.attribute("android:name").let { name ->
                    name == "com.android.vending.splits.required" || name == "com.android.vending.splits"
                }
            }.forEach { it.remove() }
        }
    }
}

fun main(arguments: Array<String>) {
    require(arguments.size in 1..2) { "Usage: AnyBrowserDeviceBuildKt <input-apk> [output-apk]" }

    val input = File(arguments[0]).canonicalFile
    require(input.isFile) { "Input APK does not exist: $input" }

    val output = arguments.getOrNull(1)?.let(::File)?.canonicalFile
    output?.parentFile?.mkdirs()

    cloneAppPatch.options["packageName"] = PROBE_PACKAGE

    val selectedPatches = setOf(gmsCoreSupportPatch, installableWithoutSplits, androidAutoAnyBrowserSupportPatch)
    val workspace = Files.createTempDirectory("any-browser-device-build")

    val sdk =
        System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error(
                "Validation requires ANDROID_HOME or ANDROID_SDK_ROOT for DEX verification"
            )
    Patcher(PatcherConfig(input, workspace.toFile(), verifier = SdkDexVerifier(File(sdk)))).use {
            patcher ->
        patcher += selectedPatches
        runBlocking {
            patcher().collect { result ->
                result.exception?.let { throw it }
                println("Applied: ${result.patch.name}")
            }
        }

        val patched = patcher.get()
        if (output == null) {
            println("Android Auto (Any) Music Browser Support applied and DEX verified with Android SDK tools: $workspace")
        } else {
            patched.applyTo(output)
            println("Unsigned isolated device probe: $output")
        }
    }
}

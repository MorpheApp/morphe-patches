/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.cardisplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.addBottomBarContainerHook
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch

internal const val CAR_DISPLAY_CLASS =
    "Lapp/morphe/extension/youtube/patches/car/CarDisplay;"

/**
 * Car hosts that show the phone screen in the car by capturing it.
 * Also declared in the manifest queries by [carDisplayManifestPatch],
 * and kept in sync with `CarDisplay.MIRRORING_CAR_HOSTS`.
 */
internal val mirroringCarHosts = listOf(
    "app.johninnl.drivedeck", "app.johninnl.drivedeck.dev",
    "app.seb3thehacker.gearslip", "app.seb3thehacker.gearslip.dev",
    "io.github.psychobarge.grapheneauto",
    "io.github.sirionero.mycar",
)

/** "any" wins when both car display patches are selected with different detection. */
internal var configuredCarDisplayDetection = "car"
internal var carLayoutPatchIncluded = false

/** Lets the app see whether its window is captured (Android 15+) and whether a mirroring car host is installed. */
private val carDisplayManifestPatch = patch {
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            if (manifest.elements("uses-permission").none {
                    it.attribute("android:name") == "android.permission.DETECT_SCREEN_RECORDING"
                }
            ) {
                manifest.root.appendChild("uses-permission") {
                    setAttribute("android:name", "android.permission.DETECT_SCREEN_RECORDING")
                }
            }

            val application = manifest.elements("application").first()
            val queries = manifest.elements("queries").firstOrNull()
                ?: manifest.root.insertChild(application.index, "queries")
            val declared = queries.elements("package").mapNotNull { it.attribute("android:name") }.toSet()
            mirroringCarHosts.filter { it !in declared }.forEach { host ->
                queries.appendChild("package") {
                    setAttribute("android:name", host)
                }
            }
        }
    }
}

/**
 * Finds the car display when the main activity is created or moved, for the car display patches.
 */
internal val carDisplayDetectionPatch = patch(
    description = "Finds whether YouTube is shown on a car display.",
) {
    dependsOn(
        sharedExtensionPatch,
        navigationBarHookPatch,
        carDisplayManifestPatch,
    )

    execute {
        // A dependency executes before the patches that set these, once per patching run.
        configuredCarDisplayDetection = "car"
        carLayoutPatchIncluded = false

        // Before the activity makes its first requests.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $CAR_DISPLAY_CLASS->onMainActivityCreate(Landroid/app/Activity;)V",
        )

        // The container of the navigation bar reports a move to another display.
        addBottomBarContainerHook(
            "$CAR_DISPLAY_CLASS->onBottomBarContainerCreated(Landroid/view/View;)V",
        )
    }

    finalize {
        fun returnConstant(method: String, smali: String) {
            mutableClassDefBy(CAR_DISPLAY_CLASS).methods.single { it.name == method }
                .addInstructions(0, smali)
        }

        returnConstant(
            "getConfiguredDetection",
            "const-string v0, \"$configuredCarDisplayDetection\"\nreturn-object v0",
        )
        returnConstant(
            "isLayoutPatchIncluded",
            "const/4 v0, ${if (carLayoutPatchIncluded) 1 else 0}\nreturn v0",
        )
    }
}

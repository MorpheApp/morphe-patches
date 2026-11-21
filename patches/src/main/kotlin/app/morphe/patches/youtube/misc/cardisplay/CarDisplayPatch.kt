/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4932
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.cardisplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.addBottomBarContainerHook
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/car/CarDisplay;"

private const val DETECT_SCREEN_RECORDING_PERMISSION = "android.permission.DETECT_SCREEN_RECORDING"

/**
 * Car hosts that show the phone screen in the car by capturing it.
 * Must be kept in sync with `CarDisplay.MIRRORING_CAR_HOSTS`.
 */
private val mirroringCarHosts = listOf(
    "app.johninnl.drivedeck",
    "app.johninnl.drivedeck.dev",
    "app.seb3thehacker.gearslip",
    "app.seb3thehacker.gearslip.dev",
    "io.github.psychobarge.grapheneauto",
    "io.github.sirionero.mycar",
)

internal val carDisplayPatch = patch(
    description = "Finds whether the main activity is shown on a car display.",
) {
    dependsOn(
        sharedExtensionPatch,
        navigationBarHookPatch,
    )

    execute {
        // Lets the app see whether its window is captured (Android 15+)
        // and whether a mirroring car host is installed.
        resourceXml("AndroidManifest.xml").use { manifest ->
            if (manifest.elements("uses-permission").none {
                    it.attribute("android:name") == DETECT_SCREEN_RECORDING_PERMISSION
                }
            ) {
                manifest.root.appendChild("uses-permission") {
                    setAttribute("android:name", DETECT_SCREEN_RECORDING_PERMISSION)
                }
            }

            val queries = manifest.elements("queries").firstOrNull()
                ?: manifest.root.insertChild(manifest.elements("application").first().index, "queries")
            val declared = queries.elements("package").mapNotNull { it.attribute("android:name") }.toSet()
            mirroringCarHosts.filter { it !in declared }.forEach { host ->
                queries.appendChild("package") {
                    setAttribute("android:name", host)
                }
            }
        }

        // Before the activity makes its first requests.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onMainActivityCreate(Landroid/app/Activity;)V",
        )

        // The container of the navigation bar is laid out again when the activity moves to another display.
        addBottomBarContainerHook(
            "$EXTENSION_CLASS->onBottomBarContainerCreated(Landroid/view/View;)V",
        )
    }
}

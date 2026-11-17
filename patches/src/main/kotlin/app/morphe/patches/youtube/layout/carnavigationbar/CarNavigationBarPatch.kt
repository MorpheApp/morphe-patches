/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.carnavigationbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.youtube.misc.cardisplay.CAR_DISPLAY_CLASS
import app.morphe.patches.youtube.misc.cardisplay.carDisplayDetectionPatch
import app.morphe.patches.youtube.misc.cardisplay.configuredCarDisplayDetection
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val CAR_NAVIGATION_BAR_CLASS =
    "Lapp/morphe/extension/youtube/patches/car/CarNavigationBar;"

private val detectionValues = mapOf("Car displays" to "car", "Any external display" to "any")
private const val DETECTION_DESCRIPTION =
    "Car displays: Android Auto, DriveDeck and hosts that use the car UI mode, and on Android 15 and later " +
        "phone mirroring by DriveDeck, Gearslip, graphene-auto or MyCar. " +
        "Any external display: also other car hosts, but also a TV or a monitor."

private var navigationBarPatchIncluded = false
private var hideDelaySeconds = 5

/**
 * On a car display, hides the bottom navigation bar after a few seconds, giving its room to the content.
 * Swipe up from the bottom edge to show it again.
 */
@Suppress("unused")
val carNavigationBarPatch = patch(
    name = "Auto-hide navigation bar on car displays",
    description = "On a car display, hides the bottom navigation bar after a few seconds, giving its room " +
        "to the content. Swipe up from the bottom edge to show it again. " +
        "Works on Android Auto for Parked Apps (early access) or DriveDeck.",
) {
    dependsOn(carDisplayDetectionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    val detection by stringOption(
        key = "detection", default = "car", values = detectionValues,
        title = "Car display detection", description = DETECTION_DESCRIPTION, required = true,
    )
    val delay by intOption(
        key = "hideDelay", default = 5, title = "Hide after (seconds)",
        description = "How long the navigation bar stays after it is shown or touched.", required = true,
    )

    execute {
        val chosen = detection ?: throw PatchException("Choose a car display detection")
        if (chosen !in detectionValues.values) throw PatchException("Unknown car display detection: $chosen")
        if (chosen == "any") configuredCarDisplayDetection = "any"
        hideDelaySeconds = (delay ?: 5).also {
            if (it !in 1..60) throw PatchException("Hide the navigation bar after 1 to 60 seconds")
        }
        navigationBarPatchIncluded = true
    }

    finalize {
        mutableClassDefBy(CAR_DISPLAY_CLASS).methods.single { it.name == "isNavigationBarPatchIncluded" }
            .addInstructions(0, "const/4 v0, ${if (navigationBarPatchIncluded) 1 else 0}\nreturn v0")
        mutableClassDefBy(CAR_NAVIGATION_BAR_CLASS).methods.single { it.name == "getConfiguredHideDelaySeconds" }
            .addInstructions(0, "const/16 v0, $hideDelaySeconds\nreturn v0")
    }
}

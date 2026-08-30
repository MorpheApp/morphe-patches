/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.refreshrate

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/ForceAppRefreshRatePatch;"

fun baseForceAppRefreshRatePatch(
    preferenceScreen: BasePreferenceScreen.Screen,
    block: BytecodePatchBuilder.() -> Unit
) = bytecodePatch(
    name = "Force app refresh rate",
    description = "Forces the app to run at a different refresh rate."
) {
    block()

    execute {
        preferenceScreen.addPreferences(
            NonInteractivePreference(
                key = "morphe_force_app_refresh_rate",
                summaryKey = null,
                tag = "app.morphe.extension.shared.settings.preference.ForceAppRefreshRateListPreference",
                selectable = true
            )
        )

        ActivityOnCreateFingerprint.matchAll().forEach {
            it.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                        "setActivityRefreshRate(Landroid/app/Activity;)V"
            )
        }

        VideoFrameReleaseHelperSetFrameRateFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getSurfaceRefreshRate(F)F
                move-result p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

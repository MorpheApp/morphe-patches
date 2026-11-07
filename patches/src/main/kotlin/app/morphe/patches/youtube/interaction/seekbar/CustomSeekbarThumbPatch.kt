/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4494
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.SeekbarOnDrawFingerprint
import app.morphe.util.fiveRegisters

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/CustomSeekbarThumbPatch;"

val customSeekbarThumbPatch = patch(
    description = "Adds an option to customize the seekbar thumb with a custom image or animated GIF."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_custom_seekbar_thumb", summary = true),
                NonInteractivePreference(
                    key = "morphe_custom_seekbar_thumb_file",
                    tag = "app.morphe.extension.youtube.settings.preference.CustomSeekbarThumbPreference",
                    selectable = true
                ),
                ListPreference("morphe_custom_seekbar_thumb_scale_type")
            )
        )

        // Shared fingerprint and indexes may have changed.
        SeekbarOnDrawFingerprint.clearMatch()
        SeekbarOnDrawFingerprint.let {
            it.method.apply {
                // Replace the call instead of inserting before it, so anything
                // other patches insert before this call is drawn below the thumb.
                val drawCircleIndex = it.instructionMatches.last().index

                replaceInstruction(
                    drawCircleIndex,
                    "invoke-static { ${fiveRegisters(drawCircleIndex)} }, $EXTENSION_CLASS->" +
                            "drawCircle(Landroid/graphics/Canvas;FFFLandroid/graphics/Paint;)V"
                )

                addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                            "setSeekbarView(Landroid/view/View;)V"
                )
            }
        }
    }
}

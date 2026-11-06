/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.SeekbarOnDrawFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/CustomSeekbarThumb;"

val customSeekbarThumbPatch = patch(
    name = "Custom seekbar thumb",
    description = "Adds an option to customize the seekbar thumb with a custom image or animated GIF."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            PreferenceCategory(
                key = "morphe_custom_seekbar_thumb_category",
                preferences = setOf(
                    SwitchPreference("morphe_custom_seekbar_thumb", summary = true),
                    NonInteractivePreference(
                        key = "morphe_custom_seekbar_thumb_file",
                        tag = "app.morphe.extension.youtube.settings.preference.CustomSeekbarThumbPreference",
                        selectable = true
                    ),
                    ListPreference(
                        key = "morphe_custom_seekbar_thumb_scale_type"
                    )
                )
            )
        )

        SeekbarOnDrawFingerprint.clearMatch()
        SeekbarOnDrawFingerprint.let {
            it.method.apply {
                val drawCircleIndex = it.instructionMatches.last().index
                val drawCircleInstruction = getInstruction<FiveRegisterInstruction>(drawCircleIndex)
                val canvasRegister = drawCircleInstruction.registerC
                val cxRegister = drawCircleInstruction.registerD
                val cyRegister = drawCircleInstruction.registerE
                val radiusRegister = drawCircleInstruction.registerF
                val paintRegister = drawCircleInstruction.registerG

                addInstructions(
                    drawCircleIndex,
                    """
                        invoke-static { v$canvasRegister, v$cxRegister, v$cyRegister, v$radiusRegister, v$paintRegister }, $EXTENSION_CLASS->drawThumb(Landroid/graphics/Canvas;FFFLandroid/graphics/Paint;)F
                        move-result v$radiusRegister
                    """
                )

                addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setSeekbarView(Landroid/view/View;)V"
                )
            }
        }
    }
}

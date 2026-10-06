/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3580
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.continuewatching

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableContinueWatchingPromptPatch;"

@Suppress("unused")
val disableContinueWatchingPromptPatch = bytecodePatch(
    name = "Disable continue watching prompt",
    description = "Adds an option to keep autoplay going instead of pausing with a Continue watching prompt after a period of inactivity.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_continue_watching_prompt", summary = true)
        )

        AutoplayInactivityLimitFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[3].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->adjustInactivityComparison(I)I
                        move-result v$register
                    """
                )
            }
        }

        AutoplayConfirmDialogEnabledFingerprint.matchOrNull()?.method?.apply {
            val register = findFreeRegister(0)

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->disableConfirmDialog()Z
                    move-result v$register
                    if-eqz v$register, :original
                    const/4 v$register, 0x0
                    return v$register
                    :original
                    nop
                """
            )
        }
    }
}

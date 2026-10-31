/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4175
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.pictureinpicture

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ChangePipActionsPatch;"


@Suppress("unused")
val changePipActionsPatch = bytecodePatch(
    name = "Change picture-in-picture actions",
    description = "Adds options to change the buttons shown in picture-in-picture mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        fun pipActionPreference(key: String) = ListPreference(
            key = key,
            entriesKey = "morphe_pip_action_entries",
            entryValuesKey = "morphe_pip_action_entry_values"
        )

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_pip_actions_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    pipActionPreference("morphe_pip_action_left"),
                    pipActionPreference("morphe_pip_action_center"),
                    pipActionPreference("morphe_pip_action_right")
                )
            )
        )

        // Change the actions right before they are set, so every update of the actions is covered.
        SetPipActionsFingerprint.let {
            it.method.apply {
                val match = it.instructionMatches.first()
                val actionsRegister = match.instruction.registersUsed[1]

                addInstructions(
                    match.index,
                    """
                        invoke-static { v$actionsRegister }, $EXTENSION_CLASS->getActions(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$actionsRegister
                    """
                )
            }
        }
    }
}

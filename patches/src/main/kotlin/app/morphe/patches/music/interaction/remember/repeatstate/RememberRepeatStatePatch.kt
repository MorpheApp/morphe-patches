/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.remember.repeatstate

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/RememberRepeatStatePatch;"

@Suppress("unused")
val rememberRepeatStatePatch = bytecodePatch(
    name = "Remember repeat state",
    description = "Adds an option to remember the repeat state when playing a new track or playlist."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_remember_repeat_state"),
        )

        val startIndex = RepeatTrackFingerprint.instructionMatches.last().index
        val moveResultIndex = RepeatTrackFingerprint.instructionMatches[4].index

        RepeatTrackFingerprint.method.apply {
            // Start index is at a branch, but the same
            // register is clobbered in both branch paths.
            val targetRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            addInstructionsWithLabels(
                startIndex,
                """
                    if-nez v$targetRegister, :skip_override
                    invoke-static { }, $EXTENSION_CLASS->rememberRepeatState()Z
                    move-result v$targetRegister
                    :skip_override
                    nop
                """
            )
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.permanentrepeat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PermanentRepeatPatch;"

@Suppress("unused")
val permanentRepeatPatch = bytecodePatch(
    name = "Permanent repeat",
    description = "Adds an option to always repeat even if the playlist ends or another track is played."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_play_permanent_repeat"),
        )

        val startIndex = RepeatTrackFingerprint.instructionMatches.last().index
        val moveResultIndex = RepeatTrackFingerprint.instructionMatches[4].index

        RepeatTrackFingerprint.method.apply {
            // Start index is at a branch, but the same
            // register is clobbered in both branch paths.
            val freeRegister = findFreeRegister(startIndex)
            val targetRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            addInstructionsWithLabels(
                startIndex,
                """
                    invoke-static { }, $EXTENSION_CLASS->permanentRepeat()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :skip_override
                    const/16 v$targetRegister, 0x1
                    :skip_override
                    nop
                """
            )
        }
    }
}

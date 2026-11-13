/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4773
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/DisableAutoCaptionsPatch;"

@Suppress("unused")
val disableAutoCaptionsPatch = patch(
    name = "Disable auto captions",
    description = "Adds an option to disable captions from being automatically enabled."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_disable_auto_captions", summary = true)
        )

        SetCaptionTrackFingerprint.let {
            it.method.apply {
                val reasonField = it.instructionMatches[0].fieldReference
                val defaultTrackMatch = it.instructionMatches.last()
                val disableOptionMatch = it.instructionMatches[4]

                // The later index is patched first.

                // Do not use the default caption track.
                val defaultTrackRegister =
                    defaultTrackMatch.getInstruction<TwoRegisterInstruction>().registerA
                addInstructions(
                    defaultTrackMatch.index + 1,
                    """
                        invoke-static { v$defaultTrackRegister }, $EXTENSION_CLASS->disableDefaultCaptionTrack(Z)Z
                        move-result v$defaultTrackRegister
                    """
                )

                // Use the option to disable captions when captions are enabled automatically.
                val disableOptionRegister =
                    disableOptionMatch.getInstruction<OneRegisterInstruction>().registerA
                val reasonRegister = findFreeRegister(disableOptionMatch.index, disableOptionRegister)
                addInstructions(
                    disableOptionMatch.index,
                    """
                        iget v$reasonRegister, p1, $reasonField
                        invoke-static { v$disableOptionRegister, v$reasonRegister }, $EXTENSION_CLASS->disableAutoCaptions(ZI)Z
                        move-result v$disableOptionRegister
                    """
                )
            }
        }
    }
}

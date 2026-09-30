/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3412
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.audio

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.interaction.swipecontrols.swipeControlsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/SoundBoostPatch;"

@Suppress("unused")
val soundBoostPatch = bytecodePatch(
    name = "Sound boost",
    description = "Adds an option to swipe the volume above the maximum level."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        swipeControlsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SWIPE_CONTROLS.addPreferences(
            SwitchPreference("morphe_volume_boost", summary = true)
        )

        AudioTrackSessionIdFingerprint.let {
            it.method.apply {
                val resultIndex = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(resultIndex).registerA

                addInstruction(
                    resultIndex + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->onAudioSessionId(I)V"
                )
            }
        }
    }
}

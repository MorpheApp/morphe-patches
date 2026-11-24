/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/DisableDRCAudioPatch;"

internal fun disableDRCAudioPatch(
    block: PatchDefinition.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
    useLegacyNormalizationFlag: PatchDefinition.() -> Boolean,
    useNormalizationFlag: PatchDefinition.() -> Boolean
) = patch(
    name = "Disable DRC audio",
    description = "Adds options to disable DRC (Dynamic Range Compression) audio and loudness normalization."
) {

    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference("morphe_disable_drc_audio_stream", summary = true),
            SwitchPreference("morphe_disable_loudness_normalization", summary = true)
        )

        // Audio formats can have a DRC version, which the player plays if DRC audio is enabled.
        // Media requests also include the preference. Both get it from the same method,
        // which all the places that create the request preferences call.
        PlaybackAgnosticUserPreferencesFingerprint.matchAll().first()
            .instructionMatches.first().getMethodCalled().apply {
                val free = findFreeRegister(0)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->disableDrcAudio()Z
                        move-result v$free
                        if-eqz v$free, :drc_audio_enabled
                        const/4 v$free, 0x0
                        return v$free
                        :drc_audio_enabled
                        nop
                    """
                )
            }

        // Nullifying the first parameter/check will disable the normalization.
        fun normalizationSmali(free: String, register: String) =
            """
                invoke-static { }, $EXTENSION_CLASS->disableLoudnessNormalization()Z
                move-result $free
                if-eqz $free, :loudness_normalization_enabled
                const/16 $register, 0x0
                :loudness_normalization_enabled
                nop
            """

        if (useNormalizationFlag()) {
            VolumeNormalizationConfigFingerprint.method.addInstructionsWithLabels(
                0,
                normalizationSmali(
                    "v0",
                    "p1"
                )
            )
        } else if (useLegacyNormalizationFlag()) {
            VolumeNormalizationConfigLegacyFingerprint.apply {
                method.apply {
                    val index = instructionMatches[3].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    val free = instructionMatches[4].getInstruction<TwoRegisterInstruction>().registerA

                    addInstructionsWithLabels(
                        index,
                        normalizationSmali(
                            "v$free",
                            "v$register"
                        )
                    )
                }
            }
        }
    }
}

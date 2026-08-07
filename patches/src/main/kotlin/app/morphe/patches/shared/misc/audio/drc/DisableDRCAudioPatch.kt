/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/DisableDRCAudioPatch;"

@Suppress("unused")
internal fun disableDRCAudioPatch(
    block: BytecodePatchBuilder.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
    useLegacyNormalizationFlag: BytecodePatchBuilder.() -> Boolean,
    useNormalizationFlag: BytecodePatchBuilder.() -> Boolean
) = bytecodePatch(
    name = "Disable DRC audio",
    description = "Adds an option to disable DRC (Dynamic Range Compression) audio."
) {

    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference("morphe_disable_drc_audio")
        )

        // Nullifying the first parameter/check will disable the normalization.
        fun normalizationSmali(free: String, register: String) =
            """
                invoke-static { }, $EXTENSION_CLASS->disableDrcAudio()Z
                move-result $free
                if-eqz $free, :disable_drc_audio
                const/16 $register, 0x0
                :disable_drc_audio
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

package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/DisableDRCAudioPatch;"
private const val SET_CONFIG_DISABLED_METHOD = "$EXTENSION_CLASS->disableDrcAudioConfig(Z)Z"
private const val SET_CONFIG_ENABLED_METHOD = "$EXTENSION_CLASS->enableDrcAudioConfig(Z)Z"

@Suppress("unused")
internal fun disableDRCAudioPatch(
    block: BytecodePatchBuilder.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
    overrideNormalizationFlag: BytecodePatchBuilder.() -> Boolean
) = bytecodePatch(
    name = "Disable DRC audio",
    description = "Adds an option to disable DRC (Dynamic Range Compression) audio."
) {

    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference("morphe_disable_drc_audio", summaryKey = null)
        )

        val compressionRatioInstructionMatches = CompressionRatioFingerprint.instructionMatches

        val formatField =
            compressionRatioInstructionMatches.first().instruction.getReference<FieldReference>()!!
        val loudnessDbField =
            compressionRatioInstructionMatches[2].instruction.getReference<FieldReference>()!!

        FormatStreamModelConstructorFingerprint.let {
            it.method.cloneMutableAndPreserveParameters().apply {
                val helperMethod = ImmutableMethod(
                    definingClass,
                    "patch_setLoudnessDb",
                    listOf(),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(7),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static {}, $EXTENSION_CLASS->disableDrcAudio()Z
                            move-result v0
                            if-eqz v0, :exit

                            # Get format field.
                            iget-object v0, p0, $formatField

                            # Set loudnessDb to 0.
                            const/4 v1, 0x0
                            iput v1, v0, $loudnessDbField

                            # Set format field.
                            iput-object v0, p0, $formatField

                            :exit
                            return-void
                        """
                    )
                }

                it.classDef.methods.add(helperMethod)

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                    addInstructionsAtControlFlowLabel(
                        index,
                        "invoke-direct/range { p0 .. p0 }, $helperMethod"
                    )
                }
            }
        }

        if (overrideNormalizationFlag()) {
            // If this flag is enabled, the DRC level will depend on other values besides loudnessDb.
            LegacyVolumeNormalizationConfigFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    SET_CONFIG_DISABLED_METHOD
                )
            }
        } else {
            FirstVolumeNormalizationConfigFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    SET_CONFIG_DISABLED_METHOD
                )
            }

            SecondVolumeNormalizationConfigFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    SET_CONFIG_DISABLED_METHOD
                )
            }

            OptionalVolumeNormalizationConfigFingerprint.let {
                val mutableMethod = it.method

                val moveResultIndex = it.instructionMatches.first().index
                val moveResultRegister = mutableMethod.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                mutableMethod.addInstructionsAtControlFlowLabel(
                    moveResultIndex + 1,
                    """
                        invoke-static { v$moveResultRegister }, $SET_CONFIG_ENABLED_METHOD
                        move-result v$moveResultRegister
                    """
                )
            }
        }
    }
}
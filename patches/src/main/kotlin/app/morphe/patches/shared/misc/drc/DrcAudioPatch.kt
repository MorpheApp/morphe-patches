package app.morphe.patches.shared.misc.drc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.FormatStreamModelConstructorFingerprint
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/patches/DrcAudioPatch;"

@Suppress("unused")
internal fun drcAudioPatch(
    block: BytecodePatchBuilder.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
    useLatestFingerprint: BytecodePatchContext.() -> Boolean = { true },
) = bytecodePatch(
    name = "Disable DRC audio",
    description = "Disables dynamic range compression (DRC) and volume normalization."
) {

    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference(
                key = "morphe_disable_drc_audio",
                tag = "app.morphe.extension.shared.settings.preference.DrcAudioSwitchPreference"
            )
        )
    }

    execute {
        val compressionFp = CompressionRatioFingerprint
        val loudnessMatch = compressionFp.instructionMatches.first()
        val loudnessDbField =
            compressionFp.method
                .getInstruction<ReferenceInstruction>(loudnessMatch.index + 1)
                .reference as FieldReference

        val formatClass = loudnessDbField.definingClass

        val formatField = compressionFp.classDef.fields.firstOrNull {
            it.type == formatClass
        } ?: error("Failed to find format field")

        val helperMethodName = "patch_setLoudnessDb"

        compressionFp.classDef.methods.add(
            ImmutableMethod(
                compressionFp.classDef.type,
                helperMethodName,
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable()
        )

        val helperMethod = compressionFp.classDef.methods.last()

        helperMethod.addInstruction(
            0,
            """
            invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->disableDrcAudio()Z
            move-result v0
            if-eqz v0, :exit
            iget-object v0, p0, $formatField
            const/4 v1, 0x0
            iput v1, v0, $loudnessDbField
            iput-object v0, p0, $formatField
            :exit
            return-void
            """.trimIndent()
        )

        FormatStreamModelConstructorFingerprint.method.addInstructionsAtControlFlowLabel(
            FormatStreamModelConstructorFingerprint.method
                .implementation!!
                .instructions
                .lastIndex,
            "invoke-direct { p0 }, ${compressionFp.classDef.type}->$helperMethodName()V"
        )

        VolumeNormalizationConfigFingerprint.method.insertLiteralOverride(
            VolumeNormalizationConfigFingerprint.instructionMatches.first().index,
            "$EXTENSION_CLASS_DESCRIPTOR->disableDrcAudioFeatureFlag(Z)Z"
        )
    }
}
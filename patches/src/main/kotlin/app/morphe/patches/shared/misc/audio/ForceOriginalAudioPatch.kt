package app.morphe.patches.shared.misc.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.util.findMethodFromToString
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/patches/ForceOriginalAudioPatch;"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun forceOriginalAudioPatch(
    block: BytecodePatchBuilder.() -> Unit = {},
    executeBlock: BytecodePatchContext.() -> Unit = {},
    fixUseLocalizedAudioTrackFlag: BytecodePatchContext.() -> Boolean,
    mainActivityOnCreateFingerprint: Fingerprint,
    subclassExtensionClassDescriptor: String,
    preferenceScreen: BasePreferenceScreen.Screen
) = bytecodePatch(
    name = "Force original audio",
    description = "Adds an option to always use the original audio track.",
) {

    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference(
                key = "morphe_force_original_audio",
                tag = "app.morphe.extension.shared.settings.preference.ForceOriginalAudioSwitchPreference"
            )
        )

        mainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $subclassExtensionClassDescriptor->setEnabled()V"
        )

        // Disable feature flag that ignores the default track flag
        // and instead overrides to the user region language.
        if (fixUseLocalizedAudioTrackFlag()) {
            SelectAudioStreamFingerprint.method.insertLiteralOverride(
                SelectAudioStreamFingerprint.instructionMatches.first().index,
                "$EXTENSION_CLASS_DESCRIPTOR->ignoreDefaultAudioStream(Z)Z"
            )
        }

        FormatStreamModelToStringFingerprint.let {
            val isDefaultAudioTrackMethod = it.originalMethod.findMethodFromToString("isDefaultAudioTrack=")
            val audioTrackDisplayNameMethod = it.originalMethod.findMethodFromToString("audioTrackDisplayName=")
            val audioTrackIdMethod = it.originalMethod.findMethodFromToString("audioTrackId=")

            it.classDef.apply {
                // Add a new field to store the override.
                val helperFieldName = "patch_isDefaultAudioTrackOverride"
                fields.add(
                    ImmutableField(
                        type,
                        helperFieldName,
                        "Ljava/lang/Boolean;",
                        // Boolean is a 100% immutable class (all fields are final)
                        // and safe to write to a shared field without volatile/synchronization,
                        // but without volatile the field can show stale data
                        // and the same field is calculated more than once by different threads.
                        AccessFlags.PRIVATE.value or AccessFlags.VOLATILE.value,
                        null,
                        null,
                        null
                    ).toMutable()
                )


                // Add a helper method because the isDefaultAudioTrack() has only 1 or 2 registers and 3 are needed.
                val helperMethodClass = type

                val originalRegisterCount = isDefaultAudioTrackMethod.implementation!!.registerCount
                if (originalRegisterCount > 3) {
                    // Patch could work if more than 3 registers are present but needs additional changes.
                    throw PatchException("Target method has more than 3 registers")
                }

                // Copy the method to add additional registers.
                val helperMethod = isDefaultAudioTrackMethod.cloneMutable(
                    name = "patch_isDefaultAudioTrack",
                    registerCount = 7
                )

                // Add the method.
                it.classDef.methods.add(helperMethod)

                helperMethod.apply {
                    val thisRegister = 3

                    // Copied method doesn't have correct registers for p0+
                    // Fix parameters registers by copying from new p0 to effectively the old p0.
                    addInstructions(
                        0,
                        """
                            # Copy old p0 to new first register.
                            move-object v${originalRegisterCount - 1} , p0
                            
                            # Save off p0 to a new high register.
                            move-object v$thisRegister, p0
                        """
                    )

                    val insertIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN)
                    val originalResultRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                    helperMethod.addInstructionsAtControlFlowLabel(
                        insertIndex,
                        """
                            iget-object v4, v$thisRegister, $helperMethodClass->$helperFieldName:Ljava/lang/Boolean;
                            if-eqz v4, :call_extension            
                            invoke-virtual { v4 }, Ljava/lang/Boolean;->booleanValue()Z
                            move-result v4
                            return v4
                            
                            :call_extension
                            invoke-virtual { v$thisRegister }, $audioTrackIdMethod
                            move-result-object v4
                            
                            invoke-virtual { v$thisRegister }, $audioTrackDisplayNameMethod
                            move-result-object v5
        
                            invoke-static { v$originalResultRegister, v4, v5 }, $EXTENSION_CLASS_DESCRIPTOR->isDefaultAudioStream(ZLjava/lang/String;Ljava/lang/String;)Z
                            move-result v4
                            
                            invoke-static { v4 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                            move-result-object v5
                            iput-object v5, v$thisRegister, $helperMethodClass->$helperFieldName:Ljava/lang/Boolean;
                            return v4
                        """
                    )
                }


                // Call new method.
                isDefaultAudioTrackMethod.addInstructions(
                    0,
                    """
                        invoke-direct { p0 }, $helperMethod
                        move-result p0
                        return p0
                    """
                )
            }
        }

        executeBlock()
    }
}

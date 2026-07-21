/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.audio.tracks

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.util.findMethodFromToString
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/ForceOriginalAudioPatch;"
private const val EXTENSION_AUDIO_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/ForceOriginalAudioPatch$AudioTrackInterface;"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun forceOriginalAudioPatch(
    block: BytecodePatchBuilder.() -> Unit = {},
    executeBlock: BytecodePatchContext.() -> Unit = {},
    fixUseLocalizedAudioTrackFlag: BytecodePatchContext.() -> Boolean,
    forcedServerAdaptiveStreaming: BytecodePatchContext.() -> Boolean,
    preferenceScreen: BasePreferenceScreen.Screen
) = bytecodePatch(
    name = "Force original audio",
    description = "Adds an option to always use the original audio track.",
) {

    block()

    dependsOn(resourceMappingPatch)

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference(
                key = "morphe_force_original_audio",
                tag = "app.morphe.extension.shared.settings.preference.ForceOriginalAudioSwitchPreference",
                summary = true
            )
        )

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

                // Clone the method to add additional registers because the
                // isDefaultAudioTrack() has only 1 or 2 registers and 3 are needed.
                val clonedMethod = isDefaultAudioTrackMethod.cloneMutable(
                    additionalRegisters = 4
                )

                // Replace existing method with cloned with more registers.
                it.classDef.methods.apply {
                    remove(isDefaultAudioTrackMethod)
                    add(clonedMethod)
                }

                clonedMethod.apply {
                    // Free registers are added
                    val free1 = isDefaultAudioTrackMethod.implementation!!.registerCount + 1
                    val free2 = free1 + 1
                    val insertIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN)
                    val originalResultRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                    clonedMethod.addInstructionsAtControlFlowLabel(
                        insertIndex,
                        """
                            iget-object v$free1, p0, $type->$helperFieldName:Ljava/lang/Boolean;
                            if-eqz v$free1, :call_extension            
                            invoke-virtual { v$free1 }, Ljava/lang/Boolean;->booleanValue()Z
                            move-result v$free1
                            return v$free1
                            
                            :call_extension
                            invoke-virtual { p0 }, $audioTrackIdMethod
                            move-result-object v$free1
                            
                            invoke-virtual { p0 }, $audioTrackDisplayNameMethod
                            move-result-object v$free2
        
                            invoke-static { v$originalResultRegister, v$free1, v$free2 }, $EXTENSION_CLASS->isDefaultAudioStream(ZLjava/lang/String;Ljava/lang/String;)Z
                            move-result v$free1
                            
                            invoke-static { v$free1 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                            move-result-object v$free2
                            iput-object v$free2, p0, $type->$helperFieldName:Ljava/lang/Boolean;
                            return v$free1
                        """
                    )
                }
            }
        }

        // Disable feature flag that ignores the default track flag
        // and instead overrides to the user region language.
        if (fixUseLocalizedAudioTrackFlag()) {
            SelectAudioStreamFingerprint.method.insertLiteralOverride(
                SelectAudioStreamFingerprint.instructionMatches.first().index,
                "$EXTENSION_CLASS->ignoreDefaultAudioStream(Z)Z"
            )
        }

        // If there is no feature flag, the SABR protocol parameter (proto buffer) must be overridden:
        // https://github.com/LuanRT/googlevideo/commit/173a2b0717c19c922e5fb53b170640a9c9d58819
        //
        // Since mapping the proto field and finding the appropriate hooking point is very difficult,
        // 'Default audio track' patches has been implemented (like 'Default video quality' patches).
        if (forcedServerAdaptiveStreaming()) {
            val audioTrackRecordClass = with(AudioTrackRecordToStringFingerprint) {
                val definingClass = classDef.type
                mapOf(
                    0 to "patch_getId",
                    1 to "patch_getDisplayName",
                    3 to "patch_getIsDefault"
                ).forEach { (matchIndex, methodName) ->
                    val fieldInstruction = instructionMatches[matchIndex].instruction
                    val fieldOpcode = fieldInstruction.opcode.name
                    val fieldReference = fieldInstruction.getReference<FieldReference>()!!
                    val fieldReturnType = fieldReference.type
                    val operation = if (fieldReturnType == "Z") {
                        "return v0"
                    } else {
                        "return-object v0"
                    }

                    val helperMethod = ImmutableMethod(
                        definingClass,
                        methodName,
                        listOf(),
                        fieldReturnType,
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                $fieldOpcode v0, p0, $fieldReference
                                $operation
                            """
                        )
                    }

                    classDef.methods.add(helperMethod)
                }

                classDef.interfaces.add(EXTENSION_AUDIO_TRACK_INTERFACE)
                definingClass
            }

            val setAudioTrackMethod =
                getAudioTrackItemOnClickFingerprint(audioTrackRecordClass)
                    .instructionMatches.last().instruction.getReference<MethodReference>()!!

            val playerControllerClass = setAudioTrackMethod.definingClass

            val audioTrackRecordArrayField =
                getCurrentAudioFormatConstructorFingerprint(audioTrackRecordClass)
                    .instructionMatches.last().instruction.getReference<FieldReference>()!!

            getSetVideoQualityListFingerprint(
                audioVideoFormatClass = audioTrackRecordArrayField.definingClass,
                playerControllerClass = playerControllerClass
            ).let {
                it.method.apply {
                    val helperMethod = ImmutableMethod(
                        definingClass,
                        "patch_setAudioTrack",
                        listOf(
                            ImmutableMethodParameter(
                                "Ljava/lang/String;",
                                null,
                                null
                            )
                        ),
                        "V",
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        val playerControllerField = it.classDef.fields.single { field ->
                            field.type == playerControllerClass
                        }
                        addInstructionsWithLabels(
                            0,
                            """
                                # Check if the audio track id is null.
                                if-eqz p1, :ignore
                                iget-object v0, p0, $playerControllerField
                                
                                # Check if the player controller class is null.
                                if-eqz v0, :ignore
                                invoke-virtual { v0, p1 }, $setAudioTrackMethod
                                
                                :ignore
                                return-void
                            """
                        )
                    }

                    it.classDef.methods.add(helperMethod)

                    val index = it.instructionMatches.first().index
                    val instruction = getInstruction<TwoRegisterInstruction>(index)
                    val freeRegister = instruction.registerA
                    val audioVideoFormatRegister = instruction.registerB

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            iget-object v$freeRegister, v$audioVideoFormatRegister, $audioTrackRecordArrayField
                            invoke-static { v$freeRegister }, $EXTENSION_CLASS->getDefaultAudioTrackId([${EXTENSION_AUDIO_TRACK_INTERFACE})Ljava/lang/String;
                            move-result-object v$freeRegister
                            invoke-direct { p0, v$freeRegister }, $helperMethod
                        """
                    )
                }
            }
        }

        executeBlock()
    }
}

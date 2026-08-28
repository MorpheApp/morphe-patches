/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.format

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.FormatStreamModelToStringFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.findMethodFromToString
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegistersLogical
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/VideoFormat;"
private const val EXTENSION_FORMAT_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/VideoFormat$FormatInterface;"

private lateinit var adaptiveFormatMethod : WeakReference<MutableMethod>

val videoFormatPatch = bytecodePatch(
    description = "Hooks YouTube to get format about the current playing video.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val formatStreamModelFormatField = FormatStreamModelToStringFingerprint
            .instructionMatches[5]
            .instruction
            .getReference<FieldReference>()!!

        getFormatFingerprint(formatStreamModelFormatField.type).classDef.apply {
            interfaces.add(EXTENSION_FORMAT_INTERFACE)

            mapOf(
                "width" to "patch_getWidth",
                "height" to "patch_getHeight",
                "mimeType" to "patch_getMimeType"
            ).forEach { (protoFieldName, patchInterfaceMethodName) ->
                val targetMethod =
                    FormatStreamModelToStringFingerprint.originalMethod.findMethodFromToString(protoFieldName)

                val instructionMatches = getFormatStreamModelMethodFingerprint(
                    formatStreamModelFormatField, targetMethod
                ).instructionMatches

                val fieldInstruction = instructionMatches[1].instruction as Instruction22c
                val returnOpcode = instructionMatches[2].instruction.opcode.name

                methods.add(
                    ImmutableMethod(
                        type,
                        patchInterfaceMethodName,
                        listOf(),
                        targetMethod.returnType,
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                ${fieldInstruction.opcode.name} v0, p0, ${fieldInstruction.reference}
                                $returnOpcode v0
                            """
                        )
                    }
                )
            }
        }

        VideoStreamingDataConstructorFingerprint.let {
            // Clone method to preserve parameters.
            it.method.cloneParameters().apply {
                // Must offset match indexes since cloning adds additional move instructions.
                val matchIndexOffset = numberOfParameterRegistersLogical
                val videoIdIndex = it.instructionMatches[1].index + matchIndexOffset
                val videoIdField = getInstruction<ReferenceInstruction>(videoIdIndex).reference
                val adaptiveFormatsIndex = it.instructionMatches.last().index + matchIndexOffset
                val adaptiveFormatsRegister = getInstruction<TwoRegisterInstruction>(adaptiveFormatsIndex).registerA
                val insertIndex = adaptiveFormatsIndex + 1
                val videoIdRegister = findFreeRegister(insertIndex, adaptiveFormatsRegister)

                addInstructions(
                    insertIndex,
                    """
                        # Get video ID.
                        move-object/from16 v$videoIdRegister, p0
                        iget-object v$videoIdRegister, v$videoIdRegister, $videoIdField
                        
                        # Override adaptive formats.
                        invoke-static { v$videoIdRegister, v$adaptiveFormatsRegister }, $EXTENSION_CLASS->hookAdaptiveFormats(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
                        move-result-object v$adaptiveFormatsRegister
                    """
                )
            }
        }

        adaptiveFormatMethod = WeakReference(HookAdaptiveFormatsFingerprint.method)
    }
}

internal fun hookAdaptiveFormat(descriptor: String) = adaptiveFormatMethod.get()!!.addInstructions(
    0,
    """
        invoke-static { p0 }, $descriptor(Ljava/util/List;)Ljava/util/List;
        move-result-object p0
    """
)
/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionsAfterSeekPatch;"

/**
 * Captions are timed cues that replace the caption on screen when they start.
 * After a long seek, the caption from before the seek stays on screen until the next cue starts:
 * - Captions are loaded in chunks of the cues that start in them, so the cue at the new
 *   position is missing if it started in an earlier chunk. Load the chunk where it starts too.
 * - The cue at the new position is loaded after the seek. YouTube shows cues added while the
 *   video time is inside them only with a server flag, so do that for caption cues.
 */
internal val captionsAfterSeekPatch = bytecodePatch(
    description = "Shows the current caption after seeking.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        CaptionChunkLoaderFingerprint.method.apply {
            val loaderClass = CaptionChunkLoaderFingerprint.classDef
            val loadChunkMethod = cloneMutable(name = "patch_loadCaptionChunk")
            loaderClass.methods.add(loadChunkMethod)

            // The caption track has the times the captions change at, which are derived and transient.
            fun captionTimesField(trackType: String) = classDefByOrNull(trackType)?.fields?.singleOrNull {
                it.type == "Ljava/util/List;" && AccessFlags.TRANSIENT.isSet(it.accessFlags)
            }
            val trackField = loaderClass.fields.single { captionTimesField(it.type) != null }
            val captionTimesField = captionTimesField(trackField.type)!!
            val register = findFreeRegister(0)

            addInstructions(
                0,
                """
                    invoke-virtual { p0, p1, p2 }, $loadChunkMethod
                    iget-object v$register, p0, ${loaderClass.type}->${trackField.name}:${trackField.type}
                    iget-object v$register, v$register, ${trackField.type}->${captionTimesField.name}:Ljava/util/List;
                    invoke-static { v$register, p1, p2 }, $EXTENSION_CLASS->getCaptionStartTime(Ljava/util/List;J)J
                    move-result-wide p1
                    invoke-virtual { p0, p1, p2 }, $loadChunkMethod
                    return-void
                """
            )
        }

        val flagMethod = EnterCueRangeOnAddFeatureFlagFingerprint.originalMethod

        fun Instruction.isFlagCall() = getReference<MethodReference>() == flagMethod

        // Method that adds a list of cue ranges.
        val addCueRangesMethod = CueRangeManagerSeekFingerprint.classDef.methods.single { method ->
            method.parameterTypes.map { it.toString() } == listOf("Ljava/util/List;") &&
                    method.indexOfFirstInstruction { isFlagCall() } >= 0
        }

        val flagIndex = addCueRangesMethod.indexOfFirstInstructionOrThrow { isFlagCall() }
        val flagRegister = addCueRangesMethod.getInstruction<OneRegisterInstruction>(flagIndex + 1).registerA

        // Cast of the added cue range.
        val castInstruction = addCueRangesMethod.getInstruction<OneRegisterInstruction>(
            addCueRangesMethod.indexOfFirstInstructionReversedOrThrow(flagIndex, Opcode.CHECK_CAST)
        )
        val cueRangeType = castInstruction.getReference<TypeReference>()!!.type
        val captionCueType = getCaptionCueConstructorFingerprint(cueRangeType).originalClassDef.type

        addCueRangesMethod.addInstructionsAtControlFlowLabel(
            flagIndex + 2,
            """
                if-nez v$flagRegister, :enabled
                instance-of v$flagRegister, v${castInstruction.registerA}, $captionCueType
                :enabled
                nop
            """
        )
    }
}

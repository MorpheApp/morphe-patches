/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3986
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
        val captionTimesField = CaptionWindowsConstructorFingerprint.instructionMatches.last()
            .fieldReference
        val loaderConstructorFingerprint =
            getCaptionChunkLoaderConstructorFingerprint(captionTimesField.definingClass)
        val captionWindowsField = loaderConstructorFingerprint.instructionMatches.last()
            .fieldReference

        getCaptionChunkLoaderFingerprint(loaderConstructorFingerprint.originalClassDef.type).let {
            it.method.apply {
                val loadChunkMethod = cloneMutable(name = "patch_loadCaptionChunk")
                it.classDef.methods.add(loadChunkMethod)
                val register = findFreeRegister(0)

                addInstructions(
                    0,
                    """
                        invoke-virtual { p0, p1, p2 }, $loadChunkMethod
                        iget-object v$register, p0, $captionWindowsField
                        iget-object v$register, v$register, $captionTimesField
                        invoke-static { v$register, p1, p2 }, $EXTENSION_CLASS->getCaptionStartTime(Ljava/util/List;J)J
                        move-result-wide p1
                        invoke-virtual { p0, p1, p2 }, $loadChunkMethod
                        return-void
                    """
                )
            }
        }

        getAddCueRangesFingerprint(EnterCueRangeOnAddFeatureFlagFingerprint.originalMethod).let {
            it.method.apply {
                val castInstruction = it.instructionMatches[1].getInstruction<OneRegisterInstruction>()
                val cueRangeType = castInstruction.getReference<TypeReference>()!!.type
                val captionCueType = getCaptionCueConstructorFingerprint(cueRangeType).originalClassDef.type

                val flagResultIndex = it.instructionMatches.last().index
                val flagRegister = getInstruction<OneRegisterInstruction>(flagResultIndex).registerA

                addInstructionsWithLabels(
                    flagResultIndex + 1,
                    """
                        if-nez v$flagRegister, :enabled
                        instance-of v$flagRegister, v${castInstruction.registerA}, $captionCueType
                        :enabled
                        nop
                    """
                )
            }
        }
    }
}

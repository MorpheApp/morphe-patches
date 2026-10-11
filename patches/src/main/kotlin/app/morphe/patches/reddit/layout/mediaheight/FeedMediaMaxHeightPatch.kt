/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaheight

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.layout.fullwidth.FeedImageMaxHeightFingerprint
import app.morphe.patches.reddit.layout.fullwidth.FeedImageSizeFingerprint
import app.morphe.patches.reddit.layout.fullwidth.FeedVideoHeightFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FeedMediaMaxHeightPatch;"

/**
 * Replaces each `width * 4 / 3` with the configured maximum height for that width.
 */
private fun MutableMethod.replaceIntMaxHeight(): Int {
    val divideByThree = literal(3, listOf(Opcode.DIV_INT_LIT8, Opcode.DIV_INT_LIT16))
    val indices = findInstructionIndicesReversed(
        literal(4, listOf(Opcode.MUL_INT_LIT8, Opcode.MUL_INT_LIT16))
    ).filter { index ->
        val multiply = getInstruction<TwoRegisterInstruction>(index)
        val divide = instructions.getOrNull(index + 1) ?: return@filter false
        divideByThree.matches(this, divide) &&
                (divide as TwoRegisterInstruction).registerB == multiply.registerA
    }

    indices.forEach { index ->
        val widthRegister = getInstruction<TwoRegisterInstruction>(index).registerB
        val resultRegister = getInstruction<TwoRegisterInstruction>(index + 1).registerA
        replaceInstruction(
            index,
            "invoke-static/range { v$widthRegister .. v$widthRegister }, $EXTENSION_CLASS->getMaxHeight(I)I"
        )
        replaceInstruction(index + 1, "move-result v$resultRegister")
    }
    return indices.size
}

/**
 * Replaces each 4:3 float constant with the configured maximum height to width ratio.
 */
private fun MutableMethod.replaceFloatMaxHeightRatio(): Int {
    val indices = findInstructionIndicesReversed(literal(4f / 3f, listOf(Opcode.CONST)))

    indices.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        replaceInstruction(index, "invoke-static { }, $EXTENSION_CLASS->getMaxHeightRatio()F")
        addInstruction(index + 1, "move-result v$register")
    }
    return indices.size
}

@Suppress("unused")
val feedMediaMaxHeightPatch = bytecodePatch(
    name = "Feed media max height",
    description = "Adds an option to change the maximum height of images, videos and galleries in the feed."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Feed media max height' requires Reddit 2026.24.0+"
            )
        }

        // Media is cropped to at most 4:3 (height to width) of the media width.
        listOf(
            FeedImageMaxHeightFingerprint,
            FeedVideoHeightFingerprint,
            FeedGalleryHeightForWidthFingerprint
        ).forEach { fingerprint ->
            if (fingerprint.method.replaceIntMaxHeight() == 0) {
                throw PatchException("Could not find the max height of ${fingerprint.method.name}")
            }
        }

        listOf(
            FeedImageSizeFingerprint,
            GalleryHeightFingerprint,
            FeedVideoFingerprint
        ).forEach { fingerprint ->
            if (fingerprint.method.replaceFloatMaxHeightRatio() == 0) {
                throw PatchException("Could not find the max height ratio of ${fingerprint.method.name}")
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

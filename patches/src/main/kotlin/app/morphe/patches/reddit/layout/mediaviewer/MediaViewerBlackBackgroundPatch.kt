/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_20_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_26_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/MediaViewerBlackBackgroundPatch;"

/**
 * Hooks the background color read at or before the given index.
 */
private fun MutableMethod.hookBackgroundColor(startIndex: Int) {
    val colorIndex = indexOfFirstInstructionReversedOrThrow(startIndex, Opcode.MOVE_RESULT_WIDE)
    val register = getInstruction<OneRegisterInstruction>(colorIndex).registerA
    addInstructions(
        colorIndex + 1,
        """
            invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getBackgroundColor(J)J
            move-result-wide v$register
        """
    )
}

@Suppress("unused")
val mediaViewerBlackBackgroundPatch = bytecodePatch(
    name = "Media viewer black background",
    description = "Adds an option to use a black background behind images and videos in the media viewer, " +
            "instead of the theme background color."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        MEDIA_VIEWER_BACKGROUND_TAGS.forEach { testTag ->
            // The background is drawn right before its test tag.
            Fingerprint(
                filters = listOf(
                    mediaViewerBackgroundCallFilter,
                    string(testTag, location = MatchAfterWithin(25))
                )
            ).matchAll().forEach { match ->
                match.method.hookBackgroundColor(match.instructionMatches.first().index)
            }
        }

        MediaViewerBottomSheetMenuFingerprint.let {
            it.method.hookBackgroundColor(it.instructionMatches.last().index)
        }

        if (is_2026_26_0_or_greater) {
            // Top fade of each page.
            MediaViewerTopGradientFingerprint.method.apply {
                hookBackgroundColor(indexOfFirstInstructionOrThrow(Opcode.MOVE_RESULT_WIDE))
            }
        }

        // Video player letterbox. Before 2026.20.0 it is always black.
        if (is_2026_20_0_or_greater) {
            // The player is shared with the feed,
            // so the extension checks the composition context is the media viewer.
            val localContextField = LocalContextFingerprint.instructionMatches.first()
                .getInstruction<ReferenceInstruction>().reference
            VideoPlayerBackgroundFingerprint.let { match ->
                match.method.apply {
                    val backgroundIndex = match.instructionMatches.last().index

                    // The theme color read, with the composer.
                    val themeRead = match.instructionMatches[1].getInstruction<FiveRegisterInstruction>()
                    val composerRegister = themeRead.registerC
                    val readLocal = (themeRead as ReferenceInstruction).reference

                    // The shape is loaded right before the background call, so its register is free.
                    val shapeIndex = backgroundIndex - 1
                    if (getInstruction(shapeIndex).opcode != Opcode.SGET_OBJECT) {
                        throw PatchException("Unexpected video player background shape")
                    }
                    val freeRegister = getInstruction<OneRegisterInstruction>(shapeIndex).registerA
                    val colorRegister = getInstruction<FiveRegisterInstruction>(backgroundIndex).registerD
                    if (maxOf(composerRegister, freeRegister, colorRegister + 1) > 15) {
                        throw PatchException("Video player background registers out of range")
                    }

                    addInstructions(
                        shapeIndex,
                        """
                            sget-object v$freeRegister, $localContextField
                            invoke-virtual { v$composerRegister, v$freeRegister }, $readLocal
                            move-result-object v$freeRegister
                            invoke-static { v$colorRegister, v${colorRegister + 1}, v$freeRegister }, $EXTENSION_CLASS->getVideoPlayerBackgroundColor(JLjava/lang/Object;)J
                            move-result-wide v$colorRegister
                        """
                    )
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/TranslatedCaptionPensPatch;"

/**
 * Auto-translated captions keep the pen (text style) ids of the original track, but the translated
 * text no longer lines up with the original segments. Channels that style their captions
 * (such as using an invisible pen for padding characters) can end up with the entire translated
 * line drawn with an invisible or black pen, so only the caption background is shown.
 *
 * Ignore all pens of auto-translated tracks so the translation uses the user caption style.
 */
internal val translatedCaptionPensPatch = bytecodePatch(
    description = "Fixes auto-translated captions of styled caption tracks showing an empty background.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        // Track whether the caption track that is about to be loaded is auto-translated.
        TimedTextUrlFingerprint.let {
            it.clearMatch() // Fingerprint is shared and indexes may no longer be correct.
            val urlIndex = it.instructionMatches[0].index
            val urlRegister = it.method.getInstruction<FiveRegisterInstruction>(urlIndex).registerD

            it.method.addInstruction(
                urlIndex,
                "invoke-static { v$urlRegister }, $EXTENSION_CLASS->setTimedTextUrl(Ljava/lang/String;)V"
            )
        }

        // Every pen lookup of the timed text parser (<pen>, <p> and <s> elements) uses the same method.
        TimedTextSegmentElementFingerprint.instructionMatches[2].getMethodCalled().addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getPenId(I)I
                move-result p1
            """
        )
    }
}

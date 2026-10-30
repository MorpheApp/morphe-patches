/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 * https://github.com/MorpheApp/morphe-patches/pull/3837
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/TranslatedCaptionPensPatch;"
private const val EXTENSION_CAPTION_REQUEST_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/TranslatedCaptionPensPatch$CaptionRequestInterface;"

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
    // Adds the caption track interface.
    dependsOn(sharedExtensionPatch, autoTranslateCaptionsPatch)

    execute {
        // Track whether the caption track that is parsed is auto-translated.
        // The track is set from the request being parsed and not from the last requested url,
        // because cached tracks are parsed without a new request.
        CaptionFileParserFingerprint.let {
            val trackField = it.instructionMatches[1].instruction.getReference<FieldReference>()!!

            mutableClassDefBy(trackField.definingClass).apply {
                interfaces.add(EXTENSION_CAPTION_REQUEST_INTERFACE)

                addHelperMethod(
                    "patch_getCaptionTrack",
                    "Ljava/lang/Object;",
                    smali = """
                        iget-object v0, p0, $trackField
                        return-object v0
                    """
                )
            }

            it.method.addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_CLASS->setParsedRequest(Ljava/lang/Object;)V"
            )
        }

        RequestResponseParserFingerprint.let {
            val requestMatch = it.instructionMatches[2]
            // Register is first written after the method start.
            val register = requestMatch.getInstruction<TwoRegisterInstruction>().registerA

            it.method.addInstructions(
                0,
                """
                    iget-object v$register, p0, ${requestMatch.instruction.getReference<FieldReference>()}
                    invoke-static { v$register }, $EXTENSION_CLASS->setParsedRequest(Ljava/lang/Object;)V
                """
            )
        }

        // Translated automatic captions can have orphan line break events,
        // and stable caption blocks use the source timing of automatic captions.
        CaptionFileParserFingerprint.instructionMatches.last().getMethodCalled().addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->repairInput(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p1
            """
        )

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

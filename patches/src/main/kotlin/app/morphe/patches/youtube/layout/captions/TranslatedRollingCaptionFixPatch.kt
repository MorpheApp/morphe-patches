/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val FIX = "Lapp/morphe/extension/youtube/patches/TranslatedRollingCaptionFix;"

/** Resolve the shared native decoder through existing caption fingerprints. */
internal val translatedRollingCaptionFixPatch =
    bytecodePatch(
        description = "Repairs orphan newline transitions in auto-translated ASR captions.",
    ) {
        dependsOn(autoTranslateCaptionsPatch, translatedCaptionPensPatch)
        execute {
            CaptionFileParserFingerprint.let {
                val decoder = it.instructionMatches.last().getMethodCalled()
                require(decoder.parameterTypes[0] == "Ljava/io/InputStream;")
                decoder.addInstructions(
                    0,
                    """
                invoke-static { p1 }, $FIX->repairInput(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p1
            """,
                )
            }
            hookParsedCaptionTrack("$FIX->setTrack(Ljava/lang/Object;)V")
        }
    }

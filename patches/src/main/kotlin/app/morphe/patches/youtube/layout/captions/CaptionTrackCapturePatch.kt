/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionTracks;"

/**
 * Observes the selected caption track and its loaded results, including cached results.
 * Does not create or enqueue any additional requests.
 */
internal val captionTrackCapturePatch = bytecodePatch(
    description = "Tracks the caption source selected by the native player.",
) {
    dependsOn(versionCheckPatch, translatedCaptionPensPatch, videoIdPatch)

    execute {
        if (!is_21_10_or_greater) return@execute

        val requestTrack = CaptionFileParserFingerprint.instructionMatches[1].fieldReference

        CaptionTrackHandlerSelectFingerprint(requestTrack).method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onSelectedTrack(Ljava/lang/Object;)V"
        )

        CaptionTrackHandlerResultFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->onNativeResult(Ljava/lang/Object;Ljava/lang/Object;)V"
        )

        hookVideoId("$EXTENSION_CLASS->onVideoId(Ljava/lang/String;)V")
    }
}

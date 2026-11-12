/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionTracks;"

private const val EXTENSION_HANDLER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/CaptionTracks$CaptionTrackHandlerInterface;"

private const val EXTENSION_UPLOADED_CALLBACK_CLASS =
    $$"Lapp/morphe/extension/youtube/patches/CaptionTracks$UploadedCaptionCallback;"

/**
 * Observes the selected caption track and its loaded results, including cached results.
 * Uses the native loader to also load the uploaded tracks of the output languages.
 */
internal val captionTrackCapturePatch = patch(
    description = "Tracks the caption source selected by the native player.",
) {
    dependsOn(versionCheckPatch, translatedCaptionPensPatch, videoIdPatch)

    execute {
        // The track handler has the methods that are hooked since 21.15.
        if (!is_21_15_or_greater) return@execute

        val requestTrack = CaptionFileParserFingerprint.instructionMatches[1].fieldReference
        // Before 21.20 the track field of the request is an Object, cast to the track after it is read.
        val trackType = if (requestTrack.type == "Ljava/lang/Object;") {
            CaptionFileParserFingerprint.method.instructions
                .dropWhile { it.getReference<FieldReference>() != requestTrack }
                .first { it.opcode == Opcode.CHECK_CAST }
                .getReference<TypeReference>()!!.type
        } else {
            requestTrack.type
        }
        val subtitleManagerType = SubtitleManagerFingerprint.classDef.type

        val handlerSelect = CaptionTrackHandlerSelectFingerprint(requestTrack, trackType, subtitleManagerType)
        val loaderField = handlerSelect.instructionMatches[0].fieldReference
        val loaderRequest = handlerSelect.instructionMatches[2].methodReference
        val callbackType = loaderRequest.parameterTypes[1].toString()
        val subtitleManagerField = handlerSelect.instructionMatches[3].fieldReference

        val selectedTrackField = SubtitleManagerSelectedTrackFingerprint(trackType)
            .instructionMatches.last().fieldReference

        val handlerResult = CaptionTrackHandlerResultFingerprint(requestTrack)

        // Make the extension callback a native request callback.
        mutableClassDefBy(EXTENSION_UPLOADED_CALLBACK_CLASS).apply {
            interfaces.add(callbackType)

            addHelperMethod(
                handlerResult.method.name,
                "V",
                parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                smali = """
                    invoke-virtual { p0, p2 }, $EXTENSION_UPLOADED_CALLBACK_CLASS->onResult(Ljava/lang/Object;)V
                    return-void
                """
            )

            addHelperMethod(
                CaptionTrackHandlerErrorFingerprint.method.name,
                "V",
                parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Exception;"),
                smali = """
                    invoke-virtual { p0, p2 }, $EXTENSION_UPLOADED_CALLBACK_CLASS->onError(Ljava/lang/Exception;)V
                    return-void
                """
            )
        }

        handlerSelect.classDef.apply {
            interfaces.add(EXTENSION_HANDLER_INTERFACE)

            addHelperMethod(
                "patch_fetchCaption",
                "V",
                parameters = listOf("Ljava/lang/Object;", EXTENSION_UPLOADED_CALLBACK_CLASS),
                freeRegisters = 2,
                smali = """
                    check-cast p1, $trackType
                    new-instance v0, ${requestTrack.definingClass}
                    invoke-direct { v0, p1 }, ${requestTrack.definingClass}-><init>($trackType)V
                    iget-object v1, p0, $loaderField
                    check-cast p2, $callbackType
                    invoke-interface { v1, v0, p2 }, $loaderRequest
                    return-void
                """
            )

            addHelperMethod(
                "patch_reloadCaption",
                "V",
                smali = """
                    iget-object v0, p0, $subtitleManagerField
                    iget-object v0, v0, $selectedTrackField
                    invoke-virtual { p0, v0 }, ${handlerSelect.method}
                    return-void
                """
            )

            addHelperMethod(
                "patch_updateCaption",
                "V",
                parameters = listOf("J", "F"),
                smali = """
                    invoke-virtual/range { p0 .. p3 }, ${CaptionTrackHandlerTimeFingerprint.method}
                    return-void
                """
            )
        }

        handlerSelect.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onSelectedTrack(Ljava/lang/Object;)V
                invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->fetchUploadedTracks(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        handlerResult.method.apply {
            // The original code reuses the parameter registers. Call a copy of it,
            // so the request and the installed result are available afterward.
            val applyResult = cloneMutable(name = "patch_applyCaptionResult")
            handlerResult.classDef.methods.add(applyResult)

            removeInstructions(0, implementation!!.instructions.count())
            addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->onNativeResult(Ljava/lang/Object;Ljava/lang/Object;)V
                    invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->mergeUploadedTracks(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p2
                    invoke-virtual/range { p0 .. p2 }, $applyResult
                    invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS->onNativeResultReady(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
                    return-void
                """
            )
        }

        hookVideoId("$EXTENSION_CLASS->onVideoId(Ljava/lang/String;)V")
    }
}

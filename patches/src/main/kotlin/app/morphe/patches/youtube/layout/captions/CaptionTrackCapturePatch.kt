/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionTracks;"

/**
 * Observes the selected caption track and its loaded results, including cached results.
 * Uses the same native loader to prefetch uploaded tracks for the requested output languages.
 */
internal val captionTrackCapturePatch = bytecodePatch(
    description = "Tracks the caption source selected by the native player.",
) {
    dependsOn(versionCheckPatch, translatedCaptionPensPatch, videoIdPatch)

    execute {
        if (!is_21_10_or_greater) return@execute

        val requestTrack = CaptionFileParserFingerprint.instructionMatches[1].fieldReference

        val select = CaptionTrackHandlerSelectFingerprint(requestTrack)
        val result = CaptionTrackHandlerResultFingerprint(requestTrack)
        val resultMethod = result.method
        val resultClass = result.classDef
        val dispatch = select.instructionMatches[1].methodReference
        val provider = select.classDef.fields.single { it.type == dispatch.definingClass }
        val callback = dispatch.parameterTypes[1].toString()
        select.classDef.interfaces.add("Lapp/morphe/extension/youtube/patches/CaptionTracks\$CaptionLoader;")
        val managerType = SubtitleManagerFingerprint.classDef.type
        val manager = select.classDef.fields.single { it.type == managerType }
        val selectedTrack = mutableClassDefBy(managerType).fields.single {
            it.type == requestTrack.type && AccessFlags.PUBLIC.isSet(it.accessFlags)
        }
        select.classDef.addHelperMethod(
            "patch_reloadCaption", "V",
            smali = """
                iget-object v0, p0, $manager
                iget-object v0, v0, $selectedTrack
                invoke-virtual { p0, v0 }, ${select.method}
                return-void
            """
        )
        val update = CaptionTrackHandlerTimeFingerprint.method
        select.classDef.addHelperMethod(
            "patch_updateCaption", "V", parameters = listOf("J", "F"),
            smali = """
                invoke-virtual/range { p0 .. p3 }, $update
                return-void
            """
        )
        select.classDef.addHelperMethod(
            "patch_fetchCaption", "V", parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            freeRegisters = 3,
            smali = """
                check-cast p1, ${requestTrack.type}
                new-instance v0, ${requestTrack.definingClass}
                invoke-direct { v0, p1 }, ${requestTrack.definingClass}-><init>(${requestTrack.type})V
                iget-object v1, p0, $provider
                const-class v2, $callback
                invoke-static { v2, p2 }, $EXTENSION_CLASS->originalCallback(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, $callback
                invoke-interface { v1, v0, v2 }, $dispatch
                return-void
            """
        )
        select.method.addInstructions(
            0, """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onSelectedTrack(Ljava/lang/Object;)V
                invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onCaptionHandler(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        resultMethod.apply {
            // The native body reuses parameter registers. Keep the request/result in this
            // wrapper so the initial native clock update sees the actual installed timeline.
            val applyResult = cloneMutable(name = "patch_applyCaptionResult")
            resultClass.methods.add(applyResult)
            removeInstructions(0, implementation!!.instructions.count())
            addInstructions(0, """
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->onNativeResult(Ljava/lang/Object;Ljava/lang/Object;)V
                invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->mergeOriginalTracks(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p2
                invoke-virtual/range { p0 .. p2 }, $applyResult
                invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS->onNativeResultReady(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
                return-void
            """)
        }

        hookVideoId("$EXTENSION_CLASS->onVideoId(Ljava/lang/String;)V")
    }
}

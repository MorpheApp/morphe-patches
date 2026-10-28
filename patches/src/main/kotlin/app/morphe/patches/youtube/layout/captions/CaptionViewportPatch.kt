/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.video.format.hookAdaptiveFormat
import app.morphe.patches.youtube.video.format.videoFormatPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val VIEWPORT = "Lapp/morphe/extension/youtube/patches/CaptionViewportPatch;"

private fun legacyCaptionFontFingerprint(cueType: String, windowType: String) =
    object :
        Fingerprint(
            classFingerprint = getCaptionOverlayFactoryFingerprint(cueType, windowType),
            returnType = "F",
            parameters = listOf("F", "I", "I"),
            filters =
                listOf(
                    methodCall("Ljava/lang/Math;->min(II)I"),
                    fieldAccess("Landroid/util/DisplayMetrics;->scaledDensity:F"),
                ),
        ) {}

internal val captionViewportPatch =
    bytecodePatch(
        description =
            "Keeps fullscreen captions proportional to fitted video on near-square screens.",
    ) {
        dependsOn(videoFormatPatch, videoIdPatch, playerTypeHookPatch)
        execute {
            val font =
                optionalCaptionSupport("caption viewport sizing") {
                    val cueType = resolveCaptionCueType()
                    val windowType =
                        getCaptionWindowSetterFingerprint(cueType).classDefOrNull?.type
                            ?: error("Caption window not found")
                    legacyCaptionFontFingerprint(cueType, windowType).methodOrNull
                        ?: error("Legacy caption font formula not found")
                } ?: return@execute
            hookVideoId("$VIEWPORT->onVideoId(Ljava/lang/String;)V")
            hookAdaptiveFormat("$VIEWPORT->onFormats")
            // The compact override never reaches this formula and remains unchanged.
            font.addInstructions(
                0,
                """
            invoke-static/range { p0 .. p3 }, $VIEWPORT->normalizeHeight(Landroid/view/View;FII)I
            move-result p3
        """,
            )
        }
    }

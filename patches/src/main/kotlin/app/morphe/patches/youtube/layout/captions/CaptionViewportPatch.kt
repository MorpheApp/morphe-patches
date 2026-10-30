/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.video.format.hookAdaptiveFormat
import app.morphe.patches.youtube.video.format.videoFormatPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionViewportPatch;"

internal val captionViewportPatch = bytecodePatch(
    description = "Keeps fullscreen captions proportional to the fitted video on near-square screens.",
) {
    dependsOn(versionCheckPatch, videoFormatPatch, videoIdPatch, playerTypeHookPatch)

    execute {
        if (!is_21_10_or_greater) {
            return@execute
        }

        hookVideoId("$EXTENSION_CLASS->onVideoId(Ljava/lang/String;)V")
        hookAdaptiveFormat("$EXTENSION_CLASS->onFormats")

        // Font size is calculated from the overlay size. Use the height of the fitted video instead.
        CaptionFontSizeFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p3 }, $EXTENSION_CLASS->normalizeHeight(Landroid/view/View;FII)I
                move-result p3
            """
        )
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.zoom

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixFullScreenZoomGesturePatch;"

@Suppress("unused")
internal val fixFullScreenZoomGesturePatch = bytecodePatch(
    description = "Forces off a flag that breaks fullscreen pinch to zoom."
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch
    )

    execute {
        FullscreenGestureZoomFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->disableBrokenZoomFlag(Z)Z"
            )
        }
    }
}

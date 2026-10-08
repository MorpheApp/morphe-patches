/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3543
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import java.lang.ref.WeakReference

private lateinit var startVideoMethod: WeakReference<MutableMethod>

val startVideoInformerPatch = bytecodePatch(
    description = "Provides shared hooks for when a video starts playing."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        startVideoMethod = WeakReference(StartVideoInformerFingerprint.method)
    }
}

fun hookVideoStarted(extensionMethod: String) {
    startVideoMethod.get()!!.addInstruction(
        0,
        "invoke-static { }, $extensionMethod"
    )
}

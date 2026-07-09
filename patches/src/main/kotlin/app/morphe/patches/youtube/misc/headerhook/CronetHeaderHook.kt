/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.headerhook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import java.lang.ref.WeakReference

private lateinit var loadHeaderMethodRef : WeakReference<MutableMethod>
private var loadHeaderIndex = 0

val cronetHeaderHookPatch = bytecodePatch(
    description = "Hooks Cronet headers.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        loadHeaderMethodRef = WeakReference(CronetHeaderFingerprint.method)
        loadHeaderIndex = CronetHeaderFingerprint.instructionMatches.first().index
    }
}

fun addHeaderHook(descriptor: String) {
    loadHeaderMethodRef.get()!!.addInstructions(
        loadHeaderIndex,
        """
            invoke-static { p1, p2 }, $descriptor
            move-result-object p2
        """
    )
}

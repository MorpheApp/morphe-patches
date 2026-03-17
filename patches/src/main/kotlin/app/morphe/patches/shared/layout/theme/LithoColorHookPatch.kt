/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.lang.ref.WeakReference

private lateinit var lithoColorOverrideHookRef : WeakReference<MutableMethod>
private var lithoColorOverrideHookInsertIndex = -1

fun lithoColorOverrideHook(targetMethodClass: String, targetMethodName: String) {
    lithoColorOverrideHookRef.get()!!.addInstructions(
        lithoColorOverrideHookInsertIndex,
        """
            invoke-static { p1 }, $targetMethodClass->$targetMethodName(I)I
            move-result p1
        """
    )
    lithoColorOverrideHookInsertIndex += 2
}

val lithoColorHookPatch = bytecodePatch(
    description = "Adds a hook to set color of Litho components.",
) {

    execute {
        lithoColorOverrideHookRef = WeakReference(LithoOnBoundsChangeFingerprint.method)
        lithoColorOverrideHookInsertIndex = LithoOnBoundsChangeFingerprint.instructionMatches.last().index - 1
    }
}

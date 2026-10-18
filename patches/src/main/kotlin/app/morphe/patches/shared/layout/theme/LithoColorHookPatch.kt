/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import java.lang.ref.WeakReference

private var lithoColorOverrideHookRef : WeakReference<MutableMethod>? = null
private var lithoColorOverrideHookInsertIndex = -1

fun lithoColorOverrideHook(targetMethodClass: String, targetMethodName: String) {
    lithoColorOverrideHookRef!!.get()!!.addInstructions(
        lithoColorOverrideHookInsertIndex,
        """
            invoke-static { p1 }, $targetMethodClass->$targetMethodName(I)I
            move-result p1
        """
    )
    lithoColorOverrideHookInsertIndex += 2
}

/**
 * Hooks the original color of a Litho background drawable, before the color overrides.
 * The patch that adds the hook must depend on [lithoColorHookPatch].
 * The method is called with the drawable and the color, and returns the color to use.
 *
 * @param methodDescriptor Method with the signature (Landroid/graphics/drawable/Drawable;I)I.
 */
fun lithoDrawableColorHook(methodDescriptor: String) {
    lithoColorOverrideHookRef!!.get()!!.apply {
        // After the color field is read into p1, before the null check of the color.
        val insertIndex = indexOfFirstInstructionReversedOrThrow(
            lithoColorOverrideHookInsertIndex,
            Opcode.IGET
        ) + 1

        addInstructions(
            insertIndex,
            """
                invoke-static { p0, p1 }, $methodDescriptor
                move-result p1
            """
        )
    }
    lithoColorOverrideHookInsertIndex += 2
}

internal fun lithoColorHookPatch(
    useModernHook: BytecodePatchBuilder.() -> Boolean
) = bytecodePatch {
    execute {
        val fingerprint = if (useModernHook()) {
            LithoOnBoundsChangeFingerprint
        } else {
            LithoOnBoundsChangeLegacyFingerprint
        }
        // Each patch that uses the hooks depends on its own instance of this patch.
        // The hooks that are already added must not be overwritten.
        if (lithoColorOverrideHookRef?.get() === fingerprint.method) {
            return@execute
        }

        lithoColorOverrideHookRef = WeakReference(fingerprint.method)
        lithoColorOverrideHookInsertIndex = fingerprint.instructionMatches.last().index - 1
    }
}

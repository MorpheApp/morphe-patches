/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.collapsedcomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.removeFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Adds Comment.patch_setCollapsed(), shared by patches that collapse comments.
 */
internal val commentCollapsedSetterPatch = patch {
    execute {
        CommentGetCollapsedFingerprint.let {
            val collapsedField = it.instructionMatches.first().getFieldAccessed()

            // Setter, so extensions can mark a comment as collapsed.
            collapsedField.removeFlags(AccessFlags.FINAL)
            it.classDef.methods.add(
                ImmutableMethod(
                    it.classDef.type,
                    "patch_setCollapsed",
                    listOf(ImmutableMethodParameter("Z", null, null)),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iput-boolean p1, p0, $collapsedField
                            return-void
                        """
                    )
                }
            )
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3517
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.collapsedcomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.cloneMutable
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RememberCollapsedCommentsPatch;"

@Suppress("unused")
val rememberCollapsedCommentsPatch = patch(
    name = "Remember collapsed comments",
    description = "Adds an option to keep comments you collapsed collapsed when reopening a post."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        commentCollapsedSetterPatch,
    )

    execute {
        // 1. Report remembered comments as collapsed. Reddit copies comments in many places
        //    (loading, merging pages, translations), so hook the getter every copy goes through.
        CommentGetCollapsedFingerprint.let {
            // The original getter, so the extension can see the flag Reddit itself set.
            it.classDef.methods.add(it.method.cloneMutable(name = "patch_getRawCollapsed"))

            // The getter has no free register, so clone it to keep "this" in p0.
            it.method.cloneParameters().apply {
                // Cloning adjusts match indexes.
                val fieldIndex = it.instructionMatches.first().index + numberOfParameterRegistersLogical
                val collapsedRegister = getInstruction<TwoRegisterInstruction>(fieldIndex).registerA

                addInstructions(
                    fieldIndex + 1,
                    """
                        invoke-static { p0, v$collapsedRegister }, $EXTENSION_CLASS->isCollapsed(${it.classDef.type}Z)Z
                        move-result v$collapsedRegister
                    """
                )
            }
        }

        // 2. Record every collapse and expand done in the comment tree.
        CommentTreeReplaceItemFingerprint.let {
            it.method.apply {
                val invokeIndex = it.instructionMatches[1].index
                val moveResultIndex = it.instructionMatches.last().index

                // invoke-interface { transform, oldItem }, Function1->invoke(Object)Object
                val oldItemRegister = getInstruction(invokeIndex).registersUsed[1]
                val newItemRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA
                if (oldItemRegister == newItemRegister) {
                    throw PatchException("Old comment tree item is overwritten by the new one")
                }

                addInstruction(
                    moveResultIndex + 1,
                    "invoke-static { v$oldItemRegister, v$newItemRegister }, " +
                            "$EXTENSION_CLASS->onCommentTreeItemUpdated(Ljava/lang/Object;Ljava/lang/Object;)V"
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

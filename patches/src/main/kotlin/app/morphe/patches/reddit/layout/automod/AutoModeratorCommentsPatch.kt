/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.automod

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.misc.collapsedcomments.commentCollapsedSetterPatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/AutoModeratorCommentsPatch;"
private const val COMMENT_TREE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/AutoModeratorCommentsPatch$CommentTree;"

@Suppress("unused")
val autoModeratorCommentsPatch = patch(
    name = "AutoModerator comments",
    description = "Adds an option to collapse or hide comments from u/AutoModerator, other bot accounts and comments pinned by moderators."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        commentCollapsedSetterPatch,
        versionCheckPatch,
    )

    execute {
        CommentsResultConstructorFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->filterComments(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
            }
        }

        // Reddit 2026.40.0 and newer also load comments as comment trees.
        if (is_2026_40_0_or_greater) {
            listOf(
                SetCommentTreesConstructorFingerprint,
                AppendCommentTreesConstructorFingerprint
            ).forEach { fingerprint ->
                fingerprint.method.addInstructions(
                    0,
                    """
                        invoke-static { p1 }, $EXTENSION_CLASS->filterCommentTrees(Ljava/util/List;)Ljava/util/List;
                        move-result-object p1
                    """
                )
            }

            CommentTreeConstructorFingerprint.let {
                it.classDef.addPatchInterface(
                    COMMENT_TREE_INTERFACE,
                    "patch_getComments",
                    it.instructionMatches.first().getFieldAccessed()
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

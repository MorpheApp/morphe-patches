/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.automod

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.collapsedcomments.commentCollapsedSetterPatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/AutoModeratorCommentsPatch;"

@Suppress("unused")
val autoModeratorCommentsPatch = patch(
    name = "AutoModerator comments",
    description = "Adds an option to collapse or hide comments from u/AutoModerator."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        commentCollapsedSetterPatch,
    )

    execute {
        SetCommentsConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->filterComments(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

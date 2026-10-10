/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.feedscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/KeepFeedPositionPatch;"

@Suppress("unused")
val keepFeedPositionPatch = bytecodePatch(
    name = "Keep feed position",
    description = "Adds an option to keep the feed at the same position after closing a post, image or video, " +
            "instead of moving the opened post to the top of the screen."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // When a post is opened, Reddit remembers its id in a "last visited post id provider".
        // When the feed becomes visible again, that id is copied into the feed state
        // (Feed.lastPostIdVisited), which re-creates the feed LazyListState with the opened
        // post as the first visible item, so the feed jumps. Home also scrolls to it directly
        // ("android_feed_pdp_scroll_anchor" experiment). Stop remembering the opened post.
        val postIdType = FeedStateToStringFingerprint.instructionMatches
            .last().getFieldAccessed().type

        rememberOpenedPostFingerprint(postIdType).method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->skipRememberOpenedPost()Z
                move-result v0
                if-eqz v0, :remember_opened_post
                return-void
                :remember_opened_post
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

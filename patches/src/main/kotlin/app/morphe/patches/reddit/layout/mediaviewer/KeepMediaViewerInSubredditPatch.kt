/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5155
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/KeepMediaViewerInSubredditPatch;"

@Suppress("unused")
val keepMediaViewerInSubredditPatch = patch(
    name = "Keep media viewer in subreddit",
    description = "Adds an option to show only posts from the same subreddit when swiping through the full screen media viewer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        fun Fingerprint.hookRequest() = method.apply {
            // Filter the loaded posts before the Listing is created, and end the list when none are left.
            val listingInitIndex = instructionMatches[3].index
            val registersUsed = getInstruction(listingInitIndex).registersUsed
            val postsRegister = registersUsed[1]
            val afterRegister = registersUsed[2]
            addInstructionsAtControlFlowLabel(
                listingInitIndex,
                """
                    invoke-static/range { v$postsRegister .. v$afterRegister }, $EXTENSION_CLASS->getAfter(Ljava/util/List;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$afterRegister
                    invoke-static/range { v$postsRegister .. v$postsRegister }, $EXTENSION_CLASS->filterPosts(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$postsRegister
                """
            )

            // Request the posts of the subreddit endpoint.
            val getListingTypeIndex = instructionMatches[1].index
            val mediaContextRegister = getInstruction(getListingTypeIndex).registersUsed[0]
            replaceInstruction(
                getListingTypeIndex,
                "invoke-static/range { v$mediaContextRegister .. v$mediaContextRegister }, " +
                        "$EXTENSION_CLASS->getListingType(Lcom/reddit/domain/model/media/MediaContext;)" +
                        "Lcom/reddit/listing/common/ListingType;"
            )
        }

        RecommendedMediaRequestFingerprint.hookRequest()
        RecommendedVideosRequestFingerprint.hookRequest()

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

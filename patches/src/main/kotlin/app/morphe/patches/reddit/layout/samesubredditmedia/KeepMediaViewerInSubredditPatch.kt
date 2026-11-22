/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5155
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.samesubredditmedia

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
        MediaViewerRequestFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val initIndex = match.instructionMatches[2].index
                val listRegister = getInstruction(initIndex).registersUsed[1]
                addInstructionsAtControlFlowLabel(
                    initIndex,
                    """
                        invoke-static/range { v$listRegister .. v$listRegister }, $EXTENSION_CLASS->filterPosts(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$listRegister
                    """
                )

                val getListingTypeIndex = match.instructionMatches[1].index
                val contextRegister = getInstruction(getListingTypeIndex).registersUsed.first()
                replaceInstruction(
                    getListingTypeIndex,
                    "invoke-static/range { v$contextRegister .. v$contextRegister }, $EXTENSION_CLASS->getListingType(Lcom/reddit/domain/model/media/MediaContext;)Lcom/reddit/listing/common/ListingType;"
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

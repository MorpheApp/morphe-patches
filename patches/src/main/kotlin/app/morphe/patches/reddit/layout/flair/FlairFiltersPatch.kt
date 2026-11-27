/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4868
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.ad.ListingFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/patches/FlairFiltersPatch;"
private const val FEED_ELEMENT_INTERFACE = $$"Lapp/morphe/extension/reddit/patches/FlairFiltersPatch$FeedElement;"
private const val FLAIR_INTERFACE = $$"Lapp/morphe/extension/reddit/patches/FlairFiltersPatch$Flair;"

@Suppress("unused")
val flairFiltersPatch = patch(
    name = "Flair filters",
    description = "Adds an option to hide feed posts with a flair of a community."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        // Old feeds.
        ListingFingerprint.let {
            val childrenMatch = it.instructionMatches.first()
            val childrenRegister = childrenMatch.getInstruction<TwoRegisterInstruction>().registerA

            it.method.addInstructions(
                childrenMatch.index,
                """
                    invoke-static { v$childrenRegister }, $EXTENSION_CLASS->filterLinks(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$childrenRegister
                """
            )
        }

        // Modern feeds.
        FeedElementProcessorFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->filterFeedElements(Ljava/util/List;)Ljava/util/List;
                move-result-object p2
            """
        )

        // The base class of all feed elements has a public link id method.
        FeedElementGetLinkIdFingerprint.classDef.interfaces.add(FEED_ELEMENT_INTERFACE)

        // Flairs of feed posts, and of the full posts that Reddit loads after a feed page is shown.
        PostFlairFeedPresentationModelConstructorFingerprint.let {
            it.classDef.addPatchInterface(FLAIR_INTERFACE, "patch_getText", it.instructionMatches[0].fieldReference)
            it.classDef.addPatchInterface(FLAIR_INTERFACE, "patch_getCommunity", it.instructionMatches[3].fieldReference)
        }

        PostFlairsElementConstructorFingerprint.let {
            val flairsMatch = it.instructionMatches.last()
            val flairsInstruction = flairsMatch.getInstruction<TwoRegisterInstruction>()

            it.method.addInstruction(
                flairsMatch.index + 1,
                "invoke-static { v${flairsInstruction.registerB}, v${flairsInstruction.registerA} }, " +
                        "$EXTENSION_CLASS->setFlairs(${FEED_ELEMENT_INTERFACE}Ljava/lang/Object;)V"
            )
        }

        LinkConstructorFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { returnIndex ->
                addInstruction(
                    returnIndex,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setLinkFlair(Lcom/reddit/domain/model/Link;)V"
                )
            }
        }

        // Home feed data has no flairs, and the full posts are not loaded for post previews.
        // Load them while there are flair filters. "Show flairs in home feed" hooks the same
        // instruction. Each hook passes the value on to the next one, so the order is not important.
        // Before 2026.24.0 Reddit selects the posts to load differently, and it is not hooked.
        if (is_2026_24_0_or_greater) {
            val postsToLoadFingerprint = if (is_2026_45_0_or_greater) {
                PostsToLoadFingerprint
            } else {
                PostsToLoadLegacyFingerprint
            }

            postsToLoadFingerprint.let {
                val flagMatch = it.instructionMatches.last()
                val flagRegister = flagMatch.getInstruction<OneRegisterInstruction>().registerA

                it.method.addInstructionsAtControlFlowLabel(
                    flagMatch.index,
                    """
                        invoke-static { v$flagRegister }, $EXTENSION_CLASS->isPostPreviewSecondaryFetchRemoved(Z)Z
                        move-result v$flagRegister
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

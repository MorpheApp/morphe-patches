/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4990
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.interactive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.ad.ListingFingerprint
import app.morphe.patches.reddit.layout.flair.FeedElementProcessorFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideInteractivePostsPatch;"
private const val FEED_ELEMENT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideInteractivePostsPatch$FeedElement;"
private const val CUSTOM_POST_LINK_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideInteractivePostsPatch$CustomPostLink;"

@Suppress("unused")
val hideInteractivePostsPatch = patch(
    name = "Hide interactive posts",
    description = "Adds an option to hide game and other interactive app posts from the home feed and other recommended feeds."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        // Old feeds, and the posts that are opened from a modern feed.
        mutableClassDefBy("Lcom/reddit/domain/model/Link;").apply {
            addPatchInterface(
                CUSTOM_POST_LINK_INTERFACE,
                "patch_isDevPlatformCustomPost",
                fields.first { it.name == "isDevPlatformCustomPost" && it.type == "Z" }
            )
        }

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

        // Modern feeds. Before 2026.24.0 the custom post feed element is not found,
        // and only old feeds are filtered.
        if (is_2026_24_0_or_greater) {
            CustomPostFeedElementConstructorFingerprint.let {
                // The base class of all feed elements has a public link id method.
                Fingerprint(
                    definingClass = it.originalClassDef.superclass!!,
                    name = "getLinkId",
                    parameters = listOf(),
                    returnType = "Ljava/lang/String;"
                ).classDef.interfaces.add(FEED_ELEMENT_INTERFACE)

                it.method.apply {
                    findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { returnIndex ->
                        addInstruction(
                            returnIndex,
                            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                                    "setCustomPost($FEED_ELEMENT_INTERFACE)V"
                        )
                    }
                }
            }

            // The feed type and the feed elements.
            FeedElementProcessorFingerprint.method.addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->filterFeedElements(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
                    move-result-object p2
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

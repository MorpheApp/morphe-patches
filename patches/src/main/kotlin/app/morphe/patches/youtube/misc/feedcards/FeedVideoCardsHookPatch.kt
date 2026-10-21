/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3758
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.feedcards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.litho.relayout.COMPONENT_HOST_CLASS
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/utils/FeedVideoCardsPatch;"

/**
 * Litho is not obfuscated, and the method overrides the framework,
 * so this matches by name on every supported app version.
 */
internal object FeedCardComponentHostLayoutFingerprint : Fingerprint(
    definingClass = COMPONENT_HOST_CLASS,
    name = "onLayout",
    returnType = "V",
    parameters = listOf("Z", "I", "I", "I", "I")
)

/**
 * Finds the full width video cards of the feeds and the video of each card,
 * for the patches that draw on the thumbnails of feed videos.
 */
internal val feedVideoCardsHookPatch = bytecodePatch(
    description = "Hooks the video cards of the feeds and finds their video ids.",
) {
    dependsOn(
        sharedExtensionPatch,
        elementProtoParserHookPatch,
        // What is drawn on a thumbnail is hidden while its video plays in the feed.
        playerTypeHookPatch,
        videoInformationPatch,
        videoIdPatch,
    )

    execute {
        // The elements of the videos include the thumbnail url with the video id,
        // and the texts of the video, which are matched to the texts of the laid out cards.
        hookElement("$EXTENSION_CLASS->onElementLoaded")

        // The next video that plays in the feed redraws the cards.
        hookVideoId("$EXTENSION_CLASS->onVideoLoaded(Ljava/lang/String;)V")

        // Feed videos are Litho components and have no views of their own,
        // so the root host of each video is found when it is laid out.
        FeedCardComponentHostLayoutFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstruction(
                    index,
                    "invoke-static { p0 }, $EXTENSION_CLASS->" +
                            "onComponentHostLayout(Lcom/facebook/litho/ComponentHost;)V"
                )
            }
        }
    }
}

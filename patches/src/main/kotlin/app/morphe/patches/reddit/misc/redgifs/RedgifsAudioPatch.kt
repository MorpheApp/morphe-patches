/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3714
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.redgifs

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_33_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RedgifsAudioPatch;"

@Suppress("unused")
val redgifsAudioPatch = bytecodePatch(
    name = "Redgifs audio",
    description = "Adds an option to play the original Redgifs video, which has audio, instead of Reddit's silent copy."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        versionCheckPatch
    )

    execute {
        // Record the Reddit video of Redgifs posts, and start fetching the Redgifs video url.
        LinkConstructorFingerprint.method.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onLinkCreated(Lcom/reddit/domain/model/Link;)V"
            )
        }

        // The feed is created from feed cells instead of posts.
        // The post url is only in the post header cell, and the video is a separate cell.
        MetadataCellToStringFingerprint.let {
            val mediaPathField = it.instructionMatches[0].getFieldAccessed()
            val idField = it.instructionMatches[3].getFieldAccessed()

            MetadataCellConstructorFingerprint.method.apply {
                // All registers are unused at the final return.
                addInstructions(
                    indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                    """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $idField
                        iget-object v0, v0, $mediaPathField
                        invoke-static { v1, v0 }, $EXTENSION_CLASS->onFeedMetadataCellCreated(Ljava/lang/String;Ljava/lang/String;)V
                    """
                )
            }
        }

        VideoElementToStringFingerprint.let {
            val postIdField = it.instructionMatches[1].getFieldAccessed()
            val urlField = it.instructionMatches[3].getFieldAccessed()

            videoElementConstructorFingerprint(urlField).method.apply {
                // All registers are unused at the final return.
                addInstructions(
                    indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                    """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $postIdField
                        iget-object v0, v0, $urlField
                        invoke-static { v1, v0 }, $EXTENSION_CLASS->onFeedVideoCreated(Ljava/lang/String;Ljava/lang/String;)V
                    """
                )
            }
        }

        // Video player url of post details and the media viewer.
        VideoUrlsConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getPlaybackUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """
        )

        if (is_2026_33_0_or_greater) {
            // Video player urls of the feed. Reddit plays the dash url if the legacy url
            // is the same, so the dash url is removed to play the Redgifs video (mp4) instead.
            DomainVideoUrlsConstructorFingerprint.method.apply {
                addInstructionsWithLabels(
                    indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                    """
                        iget-object p1, p0, $DOMAIN_VIDEO_URLS_CLASS->legacyUrl:Ljava/lang/String;
                        invoke-static { p1 }, $EXTENSION_CLASS->getPlaybackUrl(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object p2
                        if-eq p1, p2, :keep_urls
                        iput-object p2, p0, $DOMAIN_VIDEO_URLS_CLASS->legacyUrl:Ljava/lang/String;
                        const/4 p3, 0x0
                        iput-object p3, p0, $DOMAIN_VIDEO_URLS_CLASS->dashUrl:Ljava/lang/String;
                        iput-object p3, p0, $DOMAIN_VIDEO_URLS_CLASS->playbackMp4s:Lcom/reddit/domain/model/PlaybackMp4s;
                        :keep_urls
                        nop
                    """
                )
            }
        }

        // Resolve placeholder urls when the player loads them.
        DataSpecConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->resolvePlaceholderUri(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

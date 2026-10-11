/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.fullwidth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_36_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_38_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FullWidthFeedMediaPatch;"

@Suppress("unused")
val fullWidthFeedMediaPatch = bytecodePatch(
    name = "Full width feed media",
    description = "Adds an option to show images and videos edge to edge in the feed and post details, " +
            "without side padding and rounded corners."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Full width feed media' requires Reddit 2026.24.0+"
            )
        }

        // Find which boolean getter of the feed post style is 'mediaInsetEnabled'.
        val styleClass = FeedPostStyleToStringFingerprint.classDef
        val insetGetterName = mediaInsetGetterFingerprint(
            styleClass.toString(),
            FeedPostStyleToStringFingerprint.instructionMatches.last().getFieldAccessed()
        ).method.name

        feedPostStyleMediaInsetFingerprint(
            styleClass.superclass!!, insetGetterName
        ).matchAll().forEach { match ->
            match.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->isMediaInsetEnabled(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Media height is computed from the screen width minus the inset padding, regardless of
        // the post style. Without the padding the media is wider, so it must be taller to keep
        // its aspect ratio instead of being cropped.
        listOf(
            FeedImageSizeFingerprint,
            FeedImageMaxHeightFingerprint,
            FeedVideoHeightFingerprint,
            FeedGalleryHeightFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val index = fingerprint.instructionMatches[1].index + 1
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->getMediaInset(I)I
                        move-result v$register
                    """
                )
            }
        }

        // Images in the post details are inset by their own flag.
        val imageInsetField = PostImagePropsToStringFingerprint.instructionMatches.last().getFieldAccessed()
        postImagePropsConstructorFingerprint(
            PostImagePropsToStringFingerprint.classDef.toString(),
            imageInsetField
        ).matchAll().forEach { match ->
            match.method.apply {
                findInstructionIndicesReversedOrThrow(
                    fieldAccess(imageInsetField, Opcode.IPUT_BOOLEAN)
                ).forEach { index ->
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->isMediaInsetEnabled(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Post details. Older versions keep the padding there.
        if (is_2026_38_0_or_greater) {
            // Videos are padded by the post content around them.
            val propsType = PostContentPropsToStringFingerprint.classDef.type
            val contentField = PostContentPropsToStringFingerprint.instructionMatches.last().getFieldAccessed()
            val videoContentType = GifAndVideoContentToStringFingerprint.classDef.type

            Fingerprint(
                definingClass = EXTENSION_CLASS,
                name = "getPostContent"
            ).method.addInstructions(
                0,
                """
                    check-cast p0, $propsType
                    iget-object v0, p0, $contentField
                    return-object v0
                """
            )
            Fingerprint(
                definingClass = EXTENSION_CLASS,
                name = "isVideoContent"
            ).method.addInstructions(
                0,
                """
                    instance-of v0, p0, $videoContentType
                    return v0
                """
            )

            postContentLambdaConstructorFingerprint(propsType).method.addInstructions(
                0,
                """
                    invoke-static { p5, p1 }, $EXTENSION_CLASS->isPostContentInset(ZLjava/lang/Object;)Z
                    move-result p5
                """
            )

            // Shown at a precomputed size, for the padded width.
            // The method has many registers, so the parameter registers can be too high for move-result.
            PostDetailVideoFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                        invoke-static/range { p2 .. p3 }, $EXTENSION_CLASS->getPostVideoHeight(II)I
                        move-result v0
                        move/16 p3, v0
                        invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->getPostVideoWidth(I)I
                        move-result v0
                        move/16 p2, v0
                        invoke-static/range { p14 .. p14 }, $EXTENSION_CLASS->isMediaInsetEnabled(Z)Z
                        move-result v0
                        move/16 p14, v0
                    """
                )
            }
        }

        if (is_2026_36_0_or_greater) {
            // Galleries in the post details. Same register limits as videos.
            PostDetailGalleryFingerprint.method.addInstructions(
                0,
                """
                    invoke-static/range { p13 .. p13 }, $EXTENSION_CLASS->isMediaInsetEnabled(Z)Z
                    move-result v0
                    move/16 p13, v0
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

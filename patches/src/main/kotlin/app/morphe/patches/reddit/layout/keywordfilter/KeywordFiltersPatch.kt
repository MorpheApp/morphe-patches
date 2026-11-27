/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.keywordfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.ad.ListingFingerprint
import app.morphe.patches.reddit.layout.flair.FeedElementProcessorFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.shared.LinkConstructorFingerprint
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/patches/KeywordFiltersPatch;"
private const val FEED_ELEMENT_INTERFACE = $$"Lapp/morphe/extension/reddit/patches/KeywordFiltersPatch$FeedElement;"

@Suppress("unused")
val keywordFiltersPatch = patch(
    name = "Keyword filters",
    description = "Adds an option to hide feed posts with a title or text that matches a regular expression."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

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

        // Texts of post models, which include texts that modern feeds do not show.
        LinkConstructorFingerprint.method.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setLink(Lcom/reddit/domain/model/Link;)V"
            )
        }

        // The base class of all feed elements has a public link id method.
        FeedElementGetLinkIdFingerprint.classDef.interfaces.add(FEED_ELEMENT_INTERFACE)

        /**
         * After each field is set, passes the feed element and the field value to an extension method.
         *
         * @param fieldMethods Instruction match index of the field and the extension method name.
         */
        fun Fingerprint.saveTextFields(vararg fieldMethods: Pair<Int, String>) {
            // Insert from the last field, so the instruction indexes of earlier fields are unchanged.
            fieldMethods.sortedByDescending { it.first }.forEach { (matchIndex, methodName) ->
                val match = instructionMatches[matchIndex]
                val fieldInstruction = match.getInstruction<TwoRegisterInstruction>()
                val elementRegister = fieldInstruction.registerB
                val valueRegister = fieldInstruction.registerA

                method.addInstruction(
                    match.index + 1,
                    "invoke-static { v$elementRegister, v$valueRegister }, $EXTENSION_CLASS->" +
                            "$methodName(${FEED_ELEMENT_INTERFACE}Ljava/lang/String;)V"
                )
            }
        }

        PostTitleElementConstructorFingerprint.saveTextFields(
            4 to "setTitle",
            5 to "setTranslatedTitle"
        )

        PreviewTextElementConstructorFingerprint.saveTextFields(
            4 to "setPreviewText",
            7 to "setTranslatedPreviewText"
        )

        RichTextElementConstructorFingerprint.saveTextFields(
            4 to "setRichText"
        )

        ClassicPostElementConstructorFingerprint.saveTextFields(
            0 to "setTitle",
            7 to "setTranslatedTitle"
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

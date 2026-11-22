/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5189
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SearchHiddenProfilesPatch;"
private const val EMPTY_FEED_MODEL_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SearchHiddenProfilesPatch$EmptyFeedModel;"

@Suppress("unused")
val searchHiddenProfilesPatch = patch(
    name = "Search hidden profiles",
    description = "Adds an option to show a search button on profiles that hide their posts or comments. " +
            "This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Search hidden profiles' requires Reddit 2026.24.0+"
            )
        }

        ProfileEmptyFeedModelConstructorFingerprint.let {
            it.classDef.apply {
                addPatchInterface(EMPTY_FEED_MODEL_INTERFACE, "patch_getFeedType", it.instructionMatches[0].fieldReference)
                addPatchInterface(EMPTY_FEED_MODEL_INTERFACE, "patch_getProfileName", it.instructionMatches[1].fieldReference)
                addPatchInterface(EMPTY_FEED_MODEL_INTERFACE, "patch_isFiltered", it.instructionMatches[2].fieldReference)
            }
        }

        ProfileEmptyFeedFingerprint.let {
            it.method.apply {
                // Show the button of the empty feed for visitors. This is the only empty feed
                // that can show the button, so the label hook knows which label to use.
                val emptyFeedMatch = it.instructionMatches.last()
                val registers = emptyFeedMatch.instruction.registersUsed
                val showButtonRegister = registers[3]
                val clickHandlerRegister = registers[4]

                // The range call has the two registers next to each other.
                addInstructionsAtControlFlowLabel(
                    emptyFeedMatch.index,
                    """
                        invoke-static/range { v$showButtonRegister .. v$clickHandlerRegister }, $EXTENSION_CLASS->showEmptyFeedButton(ZLkotlin/jvm/functions/Function0;)Z
                        move-result v$showButtonRegister
                    """
                )

                // Replace the click handler of the button with a search.
                addInstructions(
                    0,
                    """
                        invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->getEmptyFeedButtonClickHandler(${EMPTY_FEED_MODEL_INTERFACE}Lkotlin/jvm/functions/Function0;)Lkotlin/jvm/functions/Function0;
                        move-result-object p1
                    """
                )
            }
        }

        EmptyFeedButtonLabelFingerprint.let {
            val labelMatch = it.instructionMatches.last()
            val labelRegister = labelMatch.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                labelMatch.index + 1,
                """
                    invoke-static/range { v$labelRegister .. v$labelRegister }, $EXTENSION_CLASS->getEmptyFeedButtonLabel(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$labelRegister
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

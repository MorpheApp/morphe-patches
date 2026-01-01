package app.morphe.patches.youtube.layout.hidetrendingsearchresults

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.iface.instruction.Instruction

internal var suggestionCategoryDividerHeight = -1L
    private set

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideTrendingSearchResultsPatch;"

private const val PATCH_LABEL_NAME =
    "trending_results_typing_string_empty"

@Suppress("unused")
val HideSearchTrendingResultsPatch = bytecodePatch(
    name = "Hide search trending results",
    description = "Hide the trending results under the input search box.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.37.48"
        )
    )

    execute {
        suggestionCategoryDividerHeight = getResourceId(ResourceType.DIMEN, "suggestion_category_divider_height")

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            SwitchPreference("morphe_hide_search_trending_results"),
        )

        SearchBoxTypingStringFingerprint.match(
            SearchBoxTypingMethodFingerprint.method,
        ).method.apply {
            fun getFirstRegister(instruction: Instruction): Int {
                return instruction.registersUsed.first()
            }

            val typingStringInstruction = SearchBoxTypingStringFingerprint.instructionMatches[4]
            val typingStringRegister = getFirstRegister(typingStringInstruction.instruction)
            val freeRegister = getFirstRegister(SearchBoxTypingStringFingerprint.instructionMatches.last().instruction)
            val addPatchIndex = typingStringInstruction.index

            addInstructionsWithLabels(
                addPatchIndex,

                """
                    invoke-static {v$typingStringRegister}, $EXTENSION_CLASS_DESCRIPTOR->isTypingStringEmpty(Ljava/lang/String;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :$PATCH_LABEL_NAME
                    return-void
                """,
                ExternalLabel(
                    PATCH_LABEL_NAME,
                    getInstruction(addPatchIndex)
                )
            )
        }
    }
}

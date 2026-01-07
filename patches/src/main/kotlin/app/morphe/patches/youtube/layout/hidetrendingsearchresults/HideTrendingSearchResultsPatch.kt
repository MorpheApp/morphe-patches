package app.morphe.patches.youtube.layout.hidetrendingsearchresults

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideTrendingSearchResultsPatch;"

@Suppress("unused")
val hideSearchTrendingResultsPatch = bytecodePatch(
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
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_hide_search_trending_results")
        )

        SearchBoxTypingStringFingerprint.match(
            SearchBoxTypingMethodFingerprint.method,
        ).let {
            it.method.apply {
                val stringRegisterIndex = it.instructionMatches.first().index
                val typingStringRegister = getInstruction<TwoRegisterInstruction>(stringRegisterIndex).registerA

                val insertIndex = stringRegisterIndex + 1
                val freeRegister = findFreeRegister(insertIndex, typingStringRegister)

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { v$typingStringRegister }, $EXTENSION_CLASS_DESCRIPTOR->hideTrendingSearchResult(Ljava/lang/String;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :show
                        return-void
                        :show
                        nop
                    """
                )
            }
        }
    }
}

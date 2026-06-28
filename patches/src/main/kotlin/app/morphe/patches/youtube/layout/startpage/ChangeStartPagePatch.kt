package app.morphe.patches.youtube.layout.startpage

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ChangeStartPagePatch;"

val changeStartPagePatch = bytecodePatch(
    name = "Change start page",
    description = "Adds an option to set which page the app opens in instead of the homepage.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference(
                    key = "morphe_change_start_page",
                    tag = "app.morphe.extension.shared.settings.preference.SortedListPreference"
                ),
                SwitchPreference("morphe_change_start_page_always", summary = true)
            )
        )

        // Hook browseId.
        BrowseIdFingerprint.let {
            it.method.apply {
                val browseIdIndex = it.instructionMatches.first().index
                val browseIdRegister = getInstruction<OneRegisterInstruction>(browseIdIndex).registerA

                addInstructions(
                    browseIdIndex + 1,
                    """
                        invoke-static { v$browseIdRegister }, $EXTENSION_CLASS->overrideBrowseId(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$browseIdRegister
                    """
                )
            }
        }

        // There is no browserId assigned to Shorts and Search.
        // Just hook the Intent action.
        IntentActionFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->overrideIntentAction(Landroid/content/Intent;)V",
        )

        OnBackPressedFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->handleBackPressed(Landroid/app/Activity;)Z
                move-result v0
                
                if-eqz v0, :cond_continue_back
                return-void
                :cond_continue_back
            """
        )

        OnOptionsItemSelectedFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p0 }, $EXTENSION_CLASS->handleOptionsItemSelected(Landroid/view/MenuItem;Landroid/app/Activity;)Z
                move-result v0

                if-eqz v0, :cond_continue_options
                const/4 v0, 0x1
                return v0
                :cond_continue_options
            """
        )
    }
}
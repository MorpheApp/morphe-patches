package app.morphe.patches.music.layout.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/HideGetPremiumPatch;"

@Suppress("unused")
val hideGetPremiumPatch = bytecodePatch(
    name = "Hide 'Get Music Premium'",
    description = "Adds an option to hide the \"Get Music Premium\" label in the settings and account menu.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.apps.youtube.music"(
            "7.29.52",
            "8.10.52",
            "8.37.56",
        )
    )

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_music_hide_get_premium_label"),
        )

        HideGetPremiumFingerprint.method.apply {
            val insertIndex = HideGetPremiumFingerprint.instructionMatches.last().index

            val setVisibilityInstruction = getInstruction<FiveRegisterInstruction>(insertIndex)
            val getPremiumViewRegister = setVisibilityInstruction.registerC
            val visibilityRegister = setVisibilityInstruction.registerD

            replaceInstruction(
                insertIndex,
                "const/16 v$visibilityRegister, 0x8",
            )

            addInstruction(
                insertIndex + 1,
                "invoke-virtual {v$getPremiumViewRegister, v$visibilityRegister}, " +
                    "Landroid/view/View;->setVisibility(I)V",
            )
        }

        MembershipSettingsFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideGetPremiumLabel()Z
                move-result v0
                if-eqz v0, :show
                const/4 v0, 0x0
                return-object v0
                :show
                nop
            """
        )
    }
}

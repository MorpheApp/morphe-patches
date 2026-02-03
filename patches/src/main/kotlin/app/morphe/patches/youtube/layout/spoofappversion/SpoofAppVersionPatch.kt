package app.morphe.patches.youtube.layout.spoofappversion

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_43_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_14_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/spoof/SpoofAppVersionPatch;"

val spoofAppVersionPatch = bytecodePatch(
    name = "Spoof app version",
    description = "Adds an option to trick YouTube into thinking you are running an older version of the app. " +
            "This can be used to restore old UI elements and features."
) {
    dependsOn(
        resourceMappingPatch,
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            // Group the switch and list preference together, since General menu is sorted by name
            // and the preferences can be scattered apart with non English languages.
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_spoof_app_version"),
                    if (is_20_31_or_greater) {
                        ListPreference("morphe_spoof_app_version_target")
                    } else if (is_20_14_or_greater) {
                        ListPreference(
                            key = "morphe_spoof_app_version_target",
                            entriesKey = "morphe_spoof_app_version_target_legacy_20_30_entries",
                            entryValuesKey = "morphe_spoof_app_version_target_legacy_20_30_entry_values"
                        )
                    } else if (is_19_43_or_greater) {
                        ListPreference(
                            key = "morphe_spoof_app_version_target",
                            entriesKey = "morphe_spoof_app_version_target_legacy_20_13_entries",
                            entryValuesKey = "morphe_spoof_app_version_target_legacy_20_13_entry_values"
                        )
                    } else {
                        ListPreference(
                            key = "morphe_spoof_app_version_target",
                            entriesKey = "morphe_spoof_app_version_target_legacy_19_34_entries",
                            entryValuesKey = "morphe_spoof_app_version_target_legacy_19_34_entry_values"
                        )
                    }
                )
            )
        )

        /**
         * If spoofing to target 19.20 or earlier the Library tab can crash due to
         * missing image resources. As a workaround, do not set an image in the
         * toolbar when the enum name is UNKNOWN.
         */
        ToolBarButtonFingerprint.apply {
            clearMatch() // Fingerprint is shared and indexes may no longer be correct.

            val imageResourceIndex = instructionMatches[2].index
            val register = method.getInstruction<OneRegisterInstruction>(imageResourceIndex).registerA
            val jumpIndex = instructionMatches.last().index + 1

            method.addInstructionsWithLabels(
                imageResourceIndex + 1,
                "if-eqz v$register, :ignore",
                ExternalLabel("ignore", method.getInstruction(jumpIndex))
            )
        }

        SpoofAppVersionFingerprint.apply {
            val index = instructionMatches.first().index
            val register = method.getInstruction<OneRegisterInstruction>(index).registerA

            method.addInstructions(
                index + 1,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getAppVersionOverride(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """
            )
        }

        /**
         * Flag is present in YT 20.23, but bold icons are missing and forcing them crashes the app.
         * 20.31 is the first target with all the bold icons present.
         * Fix: https://github.com/MorpheApp/morphe-patches/issues/183.
         *
         * 21.05+ these flags are no longer present.
         *
         */
        if (is_20_31_or_greater && !is_21_05_or_greater) {
            listOf(
                ShortsBoldIconsPrimaryFeatureFlagFingerprint,
                ShortsBoldIconsSecondaryFeatureFlagFingerprint,
            ).forEach { fingerprint ->
                fingerprint.let {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS_DESCRIPTOR->disableShortsBoldIcons(Z)Z"
                    )
                }
            }
        }

    }
}

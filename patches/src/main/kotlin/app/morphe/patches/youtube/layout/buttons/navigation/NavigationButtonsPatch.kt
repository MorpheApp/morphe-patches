package app.morphe.patches.youtube.layout.buttons.navigation

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.hookNavigationButtonCreated
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_19_25_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/NavigationButtonsPatch;"

val navigationButtonsPatch = bytecodePatch(
    name = "Navigation buttons",
    description = "Adds options to hide and change navigation buttons (such as the Shorts button).",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        navigationBarHookPatch,
        versionCheckPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.buttons.navigation.navigationButtonsPatch")

        val preferences = mutableSetOf(
            SwitchPreference("morphe_hide_home_button"),
            SwitchPreference("morphe_hide_shorts_button"),
            SwitchPreference("morphe_hide_create_button"),
            SwitchPreference("morphe_hide_subscriptions_button"),
            SwitchPreference("morphe_hide_notifications_button"),
            SwitchPreference("morphe_switch_create_with_notifications_button"),
            SwitchPreference("morphe_hide_navigation_button_labels"),
        )

        if (is_19_25_or_greater) {
            preferences += SwitchPreference("morphe_disable_translucent_navigation_bar_light")
            preferences += SwitchPreference("morphe_disable_translucent_navigation_bar_dark")

            PreferenceScreen.GENERAL_LAYOUT.addPreferences(
                SwitchPreference("morphe_disable_translucent_status_bar")
            )
        }

        if (is_20_15_or_greater) {
            PreferenceScreen.GENERAL_LAYOUT.addPreferences(
                SwitchPreference("morphe_navigation_bar_animations")
            )
        }

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_navigation_buttons_screen",
                sorting = Sorting.UNSORTED,
                preferences = preferences
            )
        )

        // Switch create with notifications button.
        addCreateButtonViewFingerprint.let {
            it.method.apply {
                val conditionalCheckIndex = it.instructionMatches[1].index
                val conditionRegister =
                    getInstruction<OneRegisterInstruction>(conditionalCheckIndex).registerA

                addInstructions(
                    conditionalCheckIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->switchCreateWithNotificationButton()Z
                        move-result v$conditionRegister
                    """,
                )
            }
        }

        // Hide navigation button labels.
        createPivotBarFingerprint.let {
            it.method.apply {
                val setTextIndex = it.instructionMatches.first().index
                val targetRegister = getInstruction<FiveRegisterInstruction>(setTextIndex).registerC

                addInstruction(
                    setTextIndex,
                    "invoke-static { v$targetRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->hideNavigationButtonLabels(Landroid/widget/TextView;)V",
                )
            }
        }

        // Hook navigation button created, in order to hide them.
        hookNavigationButtonCreated(EXTENSION_CLASS_DESCRIPTOR)

        // Force on/off translucent effect on status bar and navigation buttons.
        if (is_19_25_or_greater) {
            translucentNavigationStatusBarFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useTranslucentNavigationStatusBar(Z)Z",
                )
            }

            translucentNavigationButtonsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useTranslucentNavigationButtons(Z)Z",
                )
            }

            translucentNavigationButtonsSystemFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useTranslucentNavigationButtons(Z)Z",
                )
            }
        }

        if (is_20_15_or_greater) {
            animatedNavigationTabsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useAnimatedNavigationButtons(Z)Z"
                )
            }
        }
    }
}

package app.morphe.patches.youtube.layout.buttons.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.toolbar.hookToolBar
import app.morphe.patches.youtube.layout.toolbar.toolBarHookPatch
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addOSNameHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.hookNavigationButtonCreated
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_19_25_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_15_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_46_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;"

val navigationBarPatch = bytecodePatch(
    name = "Navigation bar",
    description = "Adds options to hide and change the bottom navigation bar (such as the Shorts button) "
            + " and the upper navigation toolbar. Patching version 20.21.37 and lower also adds a setting to use a wide searchbar."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
        versionCheckPatch,
        clientContextHookPatch,
        toolBarHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val navPreferences = mutableSetOf(
            SwitchPreference("morphe_hide_home_button"),
            SwitchPreference("morphe_hide_shorts_button"),
            SwitchPreference("morphe_hide_create_button"),
            SwitchPreference("morphe_hide_subscriptions_button"),
            SwitchPreference("morphe_hide_notifications_button"),
            SwitchPreference("morphe_swap_create_with_notifications_button"),
            SwitchPreference("morphe_hide_navigation_button_labels"),
            SwitchPreference("morphe_narrow_navigation_buttons"),
        )

        if (is_19_25_or_greater) {
            navPreferences += SwitchPreference("morphe_disable_translucent_navigation_bar_light")
            navPreferences += SwitchPreference("morphe_disable_translucent_navigation_bar_dark")

            PreferenceScreen.GENERAL_LAYOUT.addPreferences(
                SwitchPreference("morphe_disable_translucent_status_bar")
            )

            if (is_20_15_or_greater) {
                navPreferences += SwitchPreference("morphe_navigation_bar_animations")
            }
        }

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_navigation_buttons_screen",
                sorting = Sorting.UNSORTED,
                preferences = navPreferences
            )
        )

        // Swap create with notifications button.
        addOSNameHook(
            Endpoint.GUIDE,
            "$EXTENSION_CLASS_DESCRIPTOR->swapCreateWithNotificationButton(Ljava/lang/String;)Ljava/lang/String;",
        )

        // Hide navigation button labels.
        CreatePivotBarFingerprint.let {
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
            TranslucentNavigationStatusBarFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useTranslucentNavigationStatusBar(Z)Z",
                )
            }

            TranslucentNavigationButtonsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useTranslucentNavigationButtons(Z)Z",
                )
            }

            TranslucentNavigationButtonsSystemFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useTranslucentNavigationButtons(Z)Z",
                )
            }
        }

        if (is_20_15_or_greater) {
            AnimatedNavigationTabsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useAnimatedNavigationButtons(Z)Z"
                )
            }
        }

        if (is_20_46_or_greater) {
            // Feature interferes with translucent status bar and must be forced off.
            CollapsingToolbarLayoutFeatureFlag.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->allowCollapsingToolbarLayout(Z)Z"
                )
            }
        }

        arrayOf(
            PivotBarChangedFingerprint,
            PivotBarStyleFingerprint
        ).forEach { fingerprint ->
            fingerprint.let {
                it.method.apply {
                    val targetIndex = it.instructionMatches[1].index
                    val register = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                    addInstructions(
                        targetIndex + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->enableNarrowNavigationButton(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }


        //
        // Toolbar
        //

        val toolbarPreferences = mutableSetOf(
            SwitchPreference("morphe_hide_toolbar_create_button"),
            SwitchPreference("morphe_hide_toolbar_notification_button"),
            SwitchPreference("morphe_hide_toolbar_search_button"),
            SwitchPreference("morphe_hide_toolbar_voice_search_button")
        )
        if (!is_20_31_or_greater) {
            toolbarPreferences += SwitchPreference("morphe_wide_searchbar")
        }

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_toolbar_screen",
                sorting = Sorting.UNSORTED,
                preferences = toolbarPreferences
            )
        )

        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->hideCreateButton")
        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->hideNotificationButton")
        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->hideSearchButton")


        //
        // Hide voice search button.
        //
        SearchBarFingerprint.match(SearchBarParentFingerprint.originalClassDef).method.let { method ->
            val startIndex = SearchBarFingerprint.instructionMatches.first().index

            val setVisibilityIndex = method.indexOfFirstInstructionOrThrow(startIndex) {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.name == "setVisibility"
            }

            val instruction = method.getInstruction<FiveRegisterInstruction>(setVisibilityIndex)

            method.replaceInstruction(
                setVisibilityIndex,
                "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->hideVoiceSearchButton(Landroid/view/View;I)V"
            )
        }

        SearchResultFingerprint.match(VoiceInputControllerParentFingerprint.originalClassDef).method.let { method ->
            val voiceInputControllerCall = VoiceInputControllerFingerprint.originalMethod.run {
                "${definingClass}->${name}(${parameters.joinToString(",") { it.type }})${returnType}"
            }

            val controllerIndex = method.indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.toString() == voiceInputControllerCall
            }

            val setOnClickListenerIndex = method.indexOfFirstInstructionOrThrow(controllerIndex) {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.name == "setOnClickListener"
            }

            val viewRegister = method.getInstruction<FiveRegisterInstruction>(setOnClickListenerIndex).registerC

            method.addInstruction(
                setOnClickListenerIndex + 1,
                "invoke-static { v$viewRegister }, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->hideVoiceSearchButton(Landroid/view/View;)V"
            )
        }

        //
        // Wide searchbar
        //

        // YT removed the legacy text search text field all code required to use it.
        // This functionality could be restored by adding a search text field to the toolbar
        // with a listener that artificially clicks the toolbar search button.
        if (!is_20_31_or_greater) {
            SetWordmarkHeaderFingerprint.let {
                // Navigate to the method that checks if the YT logo is shown beside the search bar.
                val shouldShowLogoMethod = with(it.originalMethod) {
                    val invokeStaticIndex = indexOfFirstInstructionOrThrow {
                        opcode == Opcode.INVOKE_STATIC &&
                                getReference<MethodReference>()?.returnType == "Z"
                    }
                    navigate(this).to(invokeStaticIndex).stop()
                }

                shouldShowLogoMethod.apply {
                    findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                        val register = getInstruction<OneRegisterInstruction>(index).registerA

                        addInstructionsAtControlFlowLabel(
                            index,
                            """
                            invoke-static { v$register }, ${EXTENSION_CLASS_DESCRIPTOR}->enableWideSearchbar(Z)Z
                            move-result v$register
                        """
                        )
                    }
                }
            }

            // Fix missing left padding when using wide searchbar.
            WideSearchbarLayoutFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow {
                    val reference = getReference<MethodReference>()
                    reference?.definingClass == "Landroid/view/LayoutInflater;"
                            && reference.name == "inflate"
                }.forEach { inflateIndex ->
                    val register = getInstruction<OneRegisterInstruction>(inflateIndex + 1).registerA

                    addInstruction(
                        inflateIndex + 2,
                        "invoke-static { v$register }, ${EXTENSION_CLASS_DESCRIPTOR}->setActionBar(Landroid/view/View;)V"
                    )
                }
            }
        }
    }
}

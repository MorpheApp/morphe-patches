/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.immutableMethodRef
import app.morphe.patches.shared.misc.fix.proto.mutableCopyMethodRef
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethod
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addOSNameHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.addBottomBarContainerHook
import app.morphe.patches.youtube.misc.navigation.hookNavigationButtonCreated
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_45_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_46_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.toolbar.hookToolBar
import app.morphe.patches.youtube.misc.toolbar.toolBarHookPatch
import app.morphe.patches.youtube.shared.ActionBarSearchResultsFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;"

private const val EXTENSION_SETTING_INTERFACE =
    "Lapp/morphe/extension/youtube/patches/NavigationBarPatch\$SettingsController;"

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
        toolBarHookPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val navPreferences = mutableSetOf(
            SwitchPreference("morphe_hide_home_button"),
            SwitchPreference("morphe_hide_shorts_button"),
            SwitchPreference("morphe_hide_create_button"),
            SwitchPreference("morphe_hide_subscriptions_button"),
            SwitchPreference("morphe_hide_notifications_button"),
            SwitchPreference("morphe_show_search_button"),
            ListPreference("morphe_show_search_button_index"),
            SwitchPreference("morphe_show_settings_button"),
            ListPreference("morphe_show_settings_button_index"),
            SwitchPreference("morphe_show_settings_button_type"),
            SwitchPreference("morphe_swap_create_with_notifications_button"),
            SwitchPreference("morphe_hide_navigation_button_labels"),
            SwitchPreference("morphe_narrow_navigation_buttons"),
            SwitchPreference("morphe_hide_navigation_bar"),
        )

        navPreferences += SwitchPreference("morphe_disable_translucent_navigation_bar_light")
        navPreferences += SwitchPreference("morphe_disable_translucent_navigation_bar_dark")

        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_disable_translucent_status_bar")
        )

        navPreferences += SwitchPreference("morphe_navigation_bar_animations")

        if (is_20_31_or_greater) {
            navPreferences += SwitchPreference("morphe_disable_auto_hide_navigation_bar")
        }

        PreferenceScreen.GENERAL.addPreferences(
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

        // Hide navigation bar
        addBottomBarContainerHook("$EXTENSION_CLASS_DESCRIPTOR->hideNavigationBar(Landroid/view/View;)V")

        // Force on/off translucent effect on status bar and navigation buttons.
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

        AnimatedNavigationTabsFeatureFlagFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS_DESCRIPTOR->useAnimatedNavigationButtons(Z)Z"
            )
        }

        if (is_20_46_or_greater) {
            // Feature interferes with translucent status bar and must be forced off.
            CollapsingToolbarLayoutFeatureFlagFingerprint.let {
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

        if (is_20_31_or_greater) {
            AutoHideNavigationBarFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->disableAutoHidingNavigationBar()Z
                    move-result v0      
                    if-eqz v0, :show
                    return-void      
                    :show
                    nop      
                """
            )
        }

        //
        // Navigation search and settings button
        //
        ActionBarSearchResultsFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "searchQueryViewLoaded(Landroid/widget/TextView;)V"
                )
            }
        }

        PivotBarRendererFingerprint.let {
            it.method.apply {
                val pivotBarItemRendererType = it.instructionMatches[2].instruction.getReference<TypeReference>()!!.type
                val pivotBarRendererConstructorIndex = it.instructionMatches[3].index
                val pivotBarRendererConstructorReference = getInstruction<ReferenceInstruction>(pivotBarRendererConstructorIndex).reference as MethodReference
                val pivotBarRendererConstructorInstruction = getInstruction<RegisterRangeInstruction>(pivotBarRendererConstructorIndex)
                val pivotBarRendererConstructorStartRegister = pivotBarRendererConstructorInstruction.startRegister
                val pivotBarRendererConstructorEndRegister = pivotBarRendererConstructorStartRegister + pivotBarRendererConstructorInstruction.registerCount - 1
                val messageLiteIndex = pivotBarRendererConstructorReference.parameterTypes.indexOfFirst { parameterType -> parameterType == "Lcom/google/protobuf/MessageLite;" }
                val messageLiteRegister = pivotBarRendererConstructorStartRegister + messageLiteIndex + 1
                val insertIndex = it.instructionMatches.last().index
                val backupRegister = getFreeRegisterProvider(insertIndex, 1).getFreeRegister()

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        # Backup original MessageLite register using /16 to avoid 4-bit register limits
                        move-object/16 v$backupRegister, v$messageLiteRegister
        
                        # --- 1. SEARCH BUTTON ---
                        invoke-static { v$messageLiteRegister }, $EXTENSION_CLASS_DESCRIPTOR->parsePivotBarItemRenderer(Lcom/google/protobuf/MessageLite;)[B
                        move-result-object v$pivotBarRendererConstructorStartRegister
                        if-eqz v$pivotBarRendererConstructorStartRegister, :ignore_search

                        sget-object v$messageLiteRegister, $pivotBarItemRendererType->a:$pivotBarItemRendererType
                        invoke-static { v$messageLiteRegister, v$pivotBarRendererConstructorStartRegister }, $parseByteArrayMethod
                        move-result-object v$messageLiteRegister
                        check-cast v$messageLiteRegister, $pivotBarItemRendererType
        
                        new-instance v$pivotBarRendererConstructorStartRegister, ${pivotBarRendererConstructorReference.definingClass}
                        invoke-direct/range { v$pivotBarRendererConstructorStartRegister .. v$pivotBarRendererConstructorEndRegister }, $pivotBarRendererConstructorReference
        
                        invoke-static { v$pivotBarRendererConstructorStartRegister }, $EXTENSION_CLASS_DESCRIPTOR->setPivotBarRenderer(Ljava/lang/Object;)V
                        :ignore_search
        
                        # Restore MessageLite register for the next check
                        move-object/16 v$messageLiteRegister, v$backupRegister

                        # --- 2. SETTINGS BUTTON ---
                        invoke-static { v$messageLiteRegister }, $EXTENSION_CLASS_DESCRIPTOR->parseSettingsPivotBarItemRenderer(Lcom/google/protobuf/MessageLite;)[B
                        move-result-object v$pivotBarRendererConstructorStartRegister
                        if-eqz v$pivotBarRendererConstructorStartRegister, :ignore_settings

                        sget-object v$messageLiteRegister, $pivotBarItemRendererType->a:$pivotBarItemRendererType
                        invoke-static { v$messageLiteRegister, v$pivotBarRendererConstructorStartRegister }, $parseByteArrayMethod
                        move-result-object v$messageLiteRegister
                        check-cast v$messageLiteRegister, $pivotBarItemRendererType
        
                        new-instance v$pivotBarRendererConstructorStartRegister, ${pivotBarRendererConstructorReference.definingClass}
                        invoke-direct/range { v$pivotBarRendererConstructorStartRegister .. v$pivotBarRendererConstructorEndRegister }, $pivotBarRendererConstructorReference
        
                        invoke-static { v$pivotBarRendererConstructorStartRegister }, $EXTENSION_CLASS_DESCRIPTOR->setPivotBarSettingsRenderer(Ljava/lang/Object;)V
                        :ignore_settings
        
                        # Restore MessageLite register one last time for safety
                        move-object/16 v$messageLiteRegister, v$backupRegister
                        nop
                        """
                )
            }
        }

        PivotBarRendererListFingerprint.let {
            it.method.apply {
                val insertMatch = it.instructionMatches[2]
                val insertIndex = insertMatch.index
                val insertRegister =
                    getInstruction<TwoRegisterInstruction>(insertIndex).registerA

                val protoListBuilderFingerprint = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
                    returnType = insertMatch.instruction.getReference<FieldReference>()!!.type,
                    parameters = listOf("Ljava/util/Collection;")
                )
                val protoListBuilderMethod = protoListBuilderFingerprint.method

                addInstructions(
                    insertIndex,
                    """
                        # If there are objects copied to the extension, they are added to the list.
                        invoke-static { v$insertRegister }, $EXTENSION_CLASS_DESCRIPTOR->getPivotBarRendererList(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$insertRegister
                        
                        # Convert to proto list.
                        invoke-static { v$insertRegister }, $protoListBuilderMethod
                        move-result-object v$insertRegister
                    """
                )
            }
        }

        TopBarRendererPrimaryFilterFingerprint.let {
            it.method.apply {
                val onClickListenerIndex = it.instructionMatches[3].index
                val onClickListenerRegister =
                    getInstruction<FiveRegisterInstruction>(onClickListenerIndex).registerC

                val copiedButtonRendererIndex = it.instructionMatches[4].index
                val copiedButtonRendererRegister =
                    getInstruction<OneRegisterInstruction>(copiedButtonRendererIndex).registerA

                addInstruction(
                    copiedButtonRendererIndex + 1,
                    "invoke-static { v$copiedButtonRendererRegister, v$onClickListenerRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->setSearchBarOnClickListener(Lcom/google/protobuf/MessageLite;Landroid/view/View\$OnClickListener;)V"
                )
            }
        }


        //
        // Toolbar
        //

        val toolbarPreferences = mutableSetOf(
            SwitchPreference("morphe_hide_toolbar_create_button"),
            SwitchPreference("morphe_hide_toolbar_microphone_button"),
            SwitchPreference("morphe_hide_toolbar_notification_button"),
            SwitchPreference("morphe_hide_toolbar_search_button"),
            SwitchPreference("morphe_show_toolbar_settings_button"),
            ListPreference("morphe_show_toolbar_settings_button_index"),
            SwitchPreference("morphe_show_toolbar_settings_button_type")
        )
        if (!is_20_31_or_greater) {
            toolbarPreferences += SwitchPreference("morphe_wide_searchbar")
        }

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_toolbar_screen",
                sorting = Sorting.UNSORTED,
                preferences = toolbarPreferences
            )
        )

        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->hideCreateButton")
        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->hideNotificationButton")
        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->hideSearchButton")

        // Hide old search button
        //
        // Old search button appears in the Library tab when the app is first installed,
        // or when 'Disable layout update' is enabled
        // This button cannot be hidden with [toolBarHookPatch]
        OldSearchButtonVisibilityFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val instruction = getInstruction<FiveRegisterInstruction>(index)

                replaceInstruction(
                    index,
                    "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->hideOldSearchButton(Landroid/view/MenuItem;I)V"
                )
            }
        }

        // Hide microphone button in the search bar while typing.
        SearchButtonsVisibilityFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[2].index
                val instruction = getInstruction<FiveRegisterInstruction>(index)

                replaceInstruction(
                    index,
                    "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->hideMicrophoneButton(Landroid/view/View;I)V"
                )
            }
        }

        // Hide microphone button in the search bar in search results.
        SearchResultButtonVisibilityFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<FiveRegisterInstruction>(index).registerC

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "hideMicrophoneButton(Landroid/view/View;)V"
                )
            }
        }

        //
        // Show settings button
        //
        hookToolBar("$EXTENSION_CLASS_DESCRIPTOR->setToolbarSettingsOnClickListener")

        SettingIntentFingerprint.let {
            it.classDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_SETTING_INTERFACE)

                // Internal method to open YouTube settings.
                val helperMethod = ImmutableMethod(
                    type,
                    "patch_openYouTubeSettings",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, ${it.method}
                            return-void
                        """
                    )
                }

                methods.add(helperMethod)

                methods.first { method ->
                    MethodUtil.isConstructor(method)
                }.apply {
                    val index = implementation!!.instructions.lastIndex

                    addInstruction(
                        index,
                        "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->setSettingsController($EXTENSION_SETTING_INTERFACE)V"
                    )
                }
            }
        }

        var topbarButtonRendererClass: String? = null

        TopBarRendererPrimaryFilterFingerprint.let {
            it.method.apply {
                val originalButtonRendererIndex = it.instructionMatches[2].index
                topbarButtonRendererClass = getInstruction<ReferenceInstruction>(originalButtonRendererIndex).reference.toString()

                val onClickListenerIndex = it.instructionMatches[3].index
                val onClickListenerRegister = getInstruction<FiveRegisterInstruction>(onClickListenerIndex).registerC
                val copiedButtonRendererIndex = it.instructionMatches[4].index
                val copiedButtonRendererRegister = getInstruction<OneRegisterInstruction>(copiedButtonRendererIndex).registerA

                addInstruction(
                    copiedButtonRendererIndex + 1,
                    "invoke-static { v$copiedButtonRendererRegister, v$onClickListenerRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->setSearchBarOnClickListener(Lcom/google/protobuf/MessageLite;Landroid/view/View\$OnClickListener;)V"
                )
            }
        }

        TopBarRendererSecondaryFilterFingerprint.let {
            it.method.apply {
                var buttonsClass: String? = null
                val iteratorIndex = it.instructionMatches.first().index
                for (i in iteratorIndex until implementation!!.instructions.size) {
                    val instr = getInstruction(i)
                    if (instr.opcode == Opcode.CHECK_CAST) {
                        buttonsClass = (instr as ReferenceInstruction).reference.toString()
                        break
                    }
                }

                val protoListIndex = it.instructionMatches.first().index
                val protoListRegister = getInstruction<FiveRegisterInstruction>(protoListIndex).registerC
                val freeRegisters = getFreeRegisterProvider(protoListIndex, 2)
                val protoListFreeRegister = freeRegisters.getFreeRegister()
                val byteRegister = freeRegisters.getFreeRegister()

                addInstructionsWithLabels(
                    protoListIndex,
                    """
                        invoke-interface { v$protoListRegister }, ${immutableMethodRef.get()}
                        move-result v$protoListFreeRegister
                        
                        # Check if ProtoList is immutable or not.
                        if-nez v$protoListFreeRegister, :immutable
                        
                        # If mutable, copy the ProtoList.
                        invoke-static { v$protoListRegister }, ${mutableCopyMethodRef.get()}
                        move-result-object v$protoListRegister
                        
                        # Generate Settings Button Bytes (BEFORE modifying list)
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS_DESCRIPTOR->createToolbarSettingsButton(Ljava/util/List;)[B
                        move-result-object v$byteRegister
                        
                        # Modify list (removes hidden buttons to free up layout space)
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS_DESCRIPTOR->modifyToolbarButtons(Ljava/util/List;)V
                        
                        if-eqz v$byteRegister, :immutable

                        # Parse bytes back into native Buttons wrapper class
                        sget-object v$protoListFreeRegister, $buttonsClass->a:$buttonsClass
                        invoke-static { v$protoListFreeRegister, v$byteRegister }, $parseByteArrayMethod
                        move-result-object v$protoListFreeRegister
                        check-cast v$protoListFreeRegister, $buttonsClass
                        
                        # Append to the list natively
                        invoke-interface { v$protoListRegister, v$protoListFreeRegister }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                        
                        # Move to preferred index
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS_DESCRIPTOR->applyToolbarSettingsButtonIndex(Ljava/util/List;)V

                        :immutable
                        nop
                    """
                )
            }
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

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 * https://github.com/MorpheApp/morphe-patches/pull/2451
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.immutableMethodRef
import app.morphe.patches.shared.misc.fix.proto.mutableCopyMethodRef
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
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
import app.morphe.patches.youtube.misc.playservice.is_21_35_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.toolbar.hookToolBar
import app.morphe.patches.youtube.misc.toolbar.toolBarHookPatch
import app.morphe.patches.youtube.shared.ActionBarSearchResultsFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import app.morphe.util.removeFromParent
import app.morphe.util.setExtensionIsPatchIncluded
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

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;"

private const val EXTENSION_SETTING_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/NavigationBarPatch$SettingsController;"

private const val EXTENSION_SUBSCRIPTIONS_CLASS =
    "Lapp/morphe/extension/youtube/patches/ClassicSubscriptionsLayoutPatch;"

private const val EXTENSION_PIVOT_BAR_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ClassicSubscriptionsLayoutPatch$PivotBarItemInterface;"

private val hideSearchAppShortcutOption = booleanOption(
    key = "hideSearchAppShortcut",
    default = false,
    title = "Hide Search app shortcut",
    description = "Permanently hides the shortcut to open Search when long pressing the app icon in your launcher."
)

private val hideSubscriptionsAppShortcutOption = booleanOption(
    key = "hideSubscriptionsAppShortcut",
    default = false,
    title = "Hide Subscriptions app shortcut",
    description = "Permanently hides the shortcut to open Subscriptions when long pressing the app icon in your launcher."
)

private val navigationBarResourcePatch = resourcePatch {
    execute {
        val hideSearchAppShortcut by hideSearchAppShortcutOption
        val hideSubscriptionsAppShortcut by hideSubscriptionsAppShortcutOption

        // Verify the file has the expected nodes, even if the patch options are off.
        document("res/xml/main_shortcuts.xml").use { document ->
            mapOf(
                "search-shortcut" to hideSearchAppShortcut,
                "subscriptions-shortcut" to hideSubscriptionsAppShortcut
            ).forEach { (shortcutId, hide) ->
                val shortcut = document.childNodes.findElementByAttributeValueOrThrow(
                    "android:shortcutId",
                    shortcutId
                )

                if (hide == true) {
                    shortcut.removeFromParent()
                }
            }
        }
    }
}

val navigationBarPatch = bytecodePatch(
    name = "Navigation bar",
    description = "Adds options to hide and change the bottom navigation bar (such as the Shorts button) "
            + "and the upper navigation toolbar."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
        versionCheckPatch,
        clientContextHookPatch,
        toolBarHookPatch,
        fixProtoLibraryPatch,
        navigationBarResourcePatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    hideSearchAppShortcutOption()
    hideSubscriptionsAppShortcutOption()

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
            SwitchPreference("morphe_show_settings_button_type", summary = true),
            SwitchPreference("morphe_swap_create_with_notifications_button", summary = true),
            SwitchPreference("morphe_hide_navigation_bar"),
            SwitchPreference("morphe_narrow_navigation_buttons", summary = true),
            SwitchPreference("morphe_hide_navigation_button_labels"),
            SwitchPreference("morphe_hide_navigation_new_content_dot"),
            SwitchPreference("morphe_navigation_bar_animations", summary = true),
            SwitchPreference("morphe_disable_translucent_navigation", summary = true)
        )

        if (is_20_31_or_greater) {
            navPreferences += SwitchPreference("morphe_disable_auto_hide_navigation_bar", summary = true)
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
            "$EXTENSION_CLASS->swapCreateWithNotificationButton(Ljava/lang/String;)Ljava/lang/String;"
        )
        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Hide navigation button labels.
        CreatePivotBarFingerprint.let {
            it.method.apply {
                val setTextIndex = it.instructionMatches.first().index
                val targetRegister = getInstruction<FiveRegisterInstruction>(setTextIndex).registerC

                addInstruction(
                    setTextIndex,
                    "invoke-static { v$targetRegister }, " +
                            "$EXTENSION_CLASS->hideNavigationButtonLabels(Landroid/widget/TextView;)V"
                )
            }
        }

        // Hook navigation button created, in order to hide them.
        hookNavigationButtonCreated(EXTENSION_CLASS)

        // Hide navigation bar
        addBottomBarContainerHook("$EXTENSION_CLASS->hideNavigationBar(Landroid/view/View;)V")

        // Paint over the translucent status bar and navigation bar, instead of turning off the
        // feature flags that drive them. Those flags also switch the app out of edge to edge, which
        // moves the whole window layout and breaks everything measuring against it.
        addBottomBarContainerHook("$EXTENSION_CLASS->setNavigationBarOpaque(Landroid/view/View;)V")

        AnimatedNavigationTabsFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useAnimatedNavigationButtons(Z)Z"
            )
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
                            invoke-static { v$register }, $EXTENSION_CLASS->enableNarrowNavigationButton(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        if (is_20_31_or_greater) {
            listOf(
                AutoHideNavigationBarOnFeedScrollingFingerprint,
                AutoHideNavigationBarOnDismissMiniplayerFingerprint,
            ).forEach {
                it.method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->disableAutoHidingNavigationBar()Z
                        move-result v0      
                        if-eqz v0, :show
                        return-void      
                        :show
                        nop      
                    """
                )
            }
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
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "searchQueryViewLoaded(Landroid/widget/TextView;)V"
                )
            }
        }

        TranslucentNavigationButtonsSystemFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useTranslucentNavigation(Z)Z"
            )
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
                val parseByteArrayMethod = parseByteArrayMethodRef.get()!!

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        # Backup original MessageLite register using /16 to avoid 4-bit register limits
                        move-object/16 v$backupRegister, v$messageLiteRegister
        
                        # --- 1. SEARCH BUTTON ---
                        invoke-static { v$messageLiteRegister }, $EXTENSION_CLASS->parsePivotBarItemRenderer(Lcom/google/protobuf/MessageLite;)[B
                        move-result-object v$pivotBarRendererConstructorStartRegister
                        if-eqz v$pivotBarRendererConstructorStartRegister, :ignore_search

                        sget-object v$messageLiteRegister, $pivotBarItemRendererType->a:$pivotBarItemRendererType
                        invoke-static { v$messageLiteRegister, v$pivotBarRendererConstructorStartRegister }, $parseByteArrayMethod
                        move-result-object v$messageLiteRegister
                        check-cast v$messageLiteRegister, $pivotBarItemRendererType
        
                        new-instance v$pivotBarRendererConstructorStartRegister, ${pivotBarRendererConstructorReference.definingClass}
                        invoke-direct/range { v$pivotBarRendererConstructorStartRegister .. v$pivotBarRendererConstructorEndRegister }, $pivotBarRendererConstructorReference
        
                        invoke-static { v$pivotBarRendererConstructorStartRegister }, $EXTENSION_CLASS->setPivotBarRenderer(Ljava/lang/Object;)V
                        :ignore_search
        
                        # Restore MessageLite register for the next check
                        move-object/16 v$messageLiteRegister, v$backupRegister

                        # --- 2. SETTINGS BUTTON ---
                        invoke-static { v$messageLiteRegister }, $EXTENSION_CLASS->parseSettingsPivotBarItemRenderer(Lcom/google/protobuf/MessageLite;)[B
                        move-result-object v$pivotBarRendererConstructorStartRegister
                        if-eqz v$pivotBarRendererConstructorStartRegister, :ignore_settings

                        sget-object v$messageLiteRegister, $pivotBarItemRendererType->a:$pivotBarItemRendererType
                        invoke-static { v$messageLiteRegister, v$pivotBarRendererConstructorStartRegister }, $parseByteArrayMethod
                        move-result-object v$messageLiteRegister
                        check-cast v$messageLiteRegister, $pivotBarItemRendererType
        
                        new-instance v$pivotBarRendererConstructorStartRegister, ${pivotBarRendererConstructorReference.definingClass}
                        invoke-direct/range { v$pivotBarRendererConstructorStartRegister .. v$pivotBarRendererConstructorEndRegister }, $pivotBarRendererConstructorReference
        
                        invoke-static { v$pivotBarRendererConstructorStartRegister }, $EXTENSION_CLASS->setPivotBarSettingsRenderer(Ljava/lang/Object;)V
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

                val protoListBuilderMethod = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
                    returnType = insertMatch.instruction.getReference<FieldReference>()!!.type,
                    parameters = listOf("Ljava/util/Collection;")
                ).method

                addInstructions(
                    insertIndex,
                    """
                        # If there are objects copied to the extension, they are added to the list.
                        invoke-static { v$insertRegister }, $EXTENSION_CLASS->getPivotBarRendererList(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$insertRegister

                        # Convert to proto list.
                        invoke-static { v$insertRegister }, $protoListBuilderMethod
                        move-result-object v$insertRegister
                    """
                )

                if (is_21_35_or_greater) {
                    // Add back the Subscriptions button, if the server moved it to a tab of Home.
                    // The proto is created with the method used for the navigation bar shown
                    // before the guide response is loaded, so the browse endpoint extension is set.
                    // Inserted at the same index as above, so the button is added before the Search
                    // and Settings buttons.
                    val iconType = PivotBarItemProtoFactoryFingerprint.method.parameterTypes[2].toString()
                    // Enum constant names are obfuscated, so the icon type is found by its value.
                    val iconTypeLookupMethod = Fingerprint(
                        definingClass = iconType,
                        returnType = iconType,
                        parameters = listOf("I"),
                        custom = { method, _ ->
                            AccessFlags.STATIC.isSet(method.accessFlags)
                        }
                    ).originalMethod

                    val pivotBarItemOptionalType = PivotBarRendererFingerprint.method.returnType
                    // Non range invoke instructions, so 4-bit registers are required.
                    val freeRegisters = getFreeRegisterProvider(insertIndex, 4)
                    val browseIdRegister = freeRegisters.getFreeRegister4Bit()
                    val labelRegister = freeRegisters.getFreeRegister4Bit()
                    val iconRegister = freeRegisters.getFreeRegister4Bit()
                    val iconOnlyRegister = freeRegisters.getFreeRegister4Bit()

                    addInstructionsWithLabels(
                        insertIndex,
                        """
                            # Add the Subscriptions button, if the server removed it.
                            invoke-static { v$insertRegister }, $EXTENSION_SUBSCRIPTIONS_CLASS->needsSubscriptionsPivotBarItem(Ljava/util/List;)Z
                            move-result v$browseIdRegister
                            if-eqz v$browseIdRegister, :skip_subscriptions

                            const-string v$browseIdRegister, "FEsubscriptions"
                            invoke-static { }, $EXTENSION_SUBSCRIPTIONS_CLASS->getSubscriptionsLabel()Ljava/lang/String;
                            move-result-object v$labelRegister
                            invoke-static { }, $EXTENSION_SUBSCRIPTIONS_CLASS->getSubscriptionsIconType()I
                            move-result v$iconRegister
                            invoke-static { v$iconRegister }, $iconTypeLookupMethod
                            move-result-object v$iconRegister
                            const/4 v$iconOnlyRegister, 0x0
                            invoke-static { v$browseIdRegister, v$labelRegister, v$iconRegister, v$iconOnlyRegister }, ${PivotBarItemProtoFactoryFingerprint.method}
                            move-result-object v$browseIdRegister
                            invoke-static { v$browseIdRegister }, ${PivotBarRendererFingerprint.method}
                            move-result-object v$browseIdRegister
                            const/4 v$labelRegister, 0x0
                            invoke-virtual { v$browseIdRegister, v$labelRegister }, $pivotBarItemOptionalType->orElse(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$browseIdRegister

                            invoke-static { v$insertRegister, v$browseIdRegister }, $EXTENSION_SUBSCRIPTIONS_CLASS->addSubscriptionsPivotBarItem(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                            move-result-object v$insertRegister
                            :skip_subscriptions
                            nop
                        """
                    )
                }
            }
        }

        //
        // Disable the A/B layout with Subscriptions as a tab of Home.
        //

        if (is_21_35_or_greater) {
            // Allow the extension to read the proto of navigation bar items.
            PivotBarRendererFingerprint.classDef.apply {
                val messageLiteField = fields.first { field ->
                    field.type == "Lcom/google/protobuf/MessageLite;"
                }
                // The field can also be PivotBarIconOnlyItemRenderer or other renderers.
                val pivotBarItemRendererType = PivotBarRendererFingerprint.instructionMatches[2]
                    .instruction.getReference<TypeReference>()!!.type

                interfaces.add(EXTENSION_PIVOT_BAR_ITEM_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getPivotBarItemRenderer",
                        listOf(),
                        "Lcom/google/protobuf/MessageLite;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                iget-object v0, p0, $messageLiteField
                                instance-of v1, v0, $pivotBarItemRendererType
                                if-nez v1, :return
                                const/4 v0, 0x0
                                :return
                                return-object v0
                            """
                        )
                    }
                )
            }

            // Show only the selected tab of the Home and Subscriptions feeds.
            // The tab protos are filtered instead of the browse response,
            // because the response would lose the proto extensions if it's parsed again.
            BrowseResponseTabsFingerprint.let {
                it.method.apply {
                    val streamIndex = it.instructionMatches[1].index
                    val tabsRegister = getInstruction<FiveRegisterInstruction>(streamIndex).registerC

                    addInstructions(
                        streamIndex,
                        """
                            invoke-static { v$tabsRegister }, $EXTENSION_SUBSCRIPTIONS_CLASS->filterBrowseTabs(Ljava/util/List;)Ljava/util/List;
                            move-result-object v$tabsRegister
                        """
                    )
                }
            }

            OfflineNavigationBarSubscriptionsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_SUBSCRIPTIONS_CLASS->hideOfflineSubscriptionsButton(Z)Z"
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
                            $$"$$EXTENSION_CLASS->setSearchBarOnClickListener(Lcom/google/protobuf/MessageLite;Landroid/view/View$OnClickListener;)V"
                )
            }
        }


        //
        // Toolbar
        //

        val toolbarPreferences = mutableSetOf(
            SwitchPreference("morphe_hide_toolbar_cast_button"),
            SwitchPreference("morphe_hide_toolbar_chat_button"),
            SwitchPreference("morphe_hide_toolbar_create_button", titleKey = "morphe_hide_create_button_title"),
            SwitchPreference("morphe_hide_toolbar_microphone_button"),
            SwitchPreference("morphe_hide_toolbar_notification_button", titleKey = "morphe_hide_notifications_button_title"),
            SwitchPreference("morphe_hide_toolbar_search_button"),
            SwitchPreference("morphe_show_toolbar_settings_button", titleKey = "morphe_show_settings_button_title"),
            ListPreference("morphe_show_toolbar_settings_button_index", titleKey = "morphe_show_settings_button_index_title"),
            SwitchPreference("morphe_show_toolbar_settings_button_type", titleKey = "morphe_show_settings_button_type_title", summary = true)
        )

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_toolbar_screen",
                sorting = Sorting.UNSORTED,
                preferences = toolbarPreferences
            )
        )

        hookToolBar("$EXTENSION_CLASS->hideCreateButton")
        hookToolBar("$EXTENSION_CLASS->hideNotificationButton")
        hookToolBar("$EXTENSION_CLASS->hideSearchButton")
        hookToolBar("$EXTENSION_CLASS->hideChatButton")

        //
        // Hide cast button
        //
        CastMenuItemInitializeFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val menuItemRegister = getInstruction<FiveRegisterInstruction>(index).registerC

                addInstruction(
                    index,
                    "invoke-static { v$menuItemRegister }, $EXTENSION_CLASS->hideCastButton(Landroid/view/MenuItem;)V"
                )
            }
        }

        CastMenuItemVisibilityFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val visibilityRegister = getInstruction<FiveRegisterInstruction>(index).registerD

                addInstructions(
                    index,
                    """
                        invoke-static { v$visibilityRegister }, $EXTENSION_CLASS->hideCastButton(Z)Z
                        move-result v$visibilityRegister
                    """
                )
            }
        }

        //
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
                            "$EXTENSION_CLASS->hideOldSearchButton(Landroid/view/MenuItem;I)V"
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
                            "$EXTENSION_CLASS->hideMicrophoneButton(Landroid/view/View;I)V"
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
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "hideMicrophoneButton(Landroid/view/View;)V"
                )
            }
        }

        //
        // Show settings button
        //
        hookToolBar("$EXTENSION_CLASS->setToolbarSettingsOnClickListener")

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
                    // 21.25+ has an ignored MenuItem parameter.
                    val parameters = when (it.method.parameters.size) {
                        0 -> ""
                        1 -> ", v0"
                        else -> throw PatchException("Unpexpected number of parameters")
                    }

                    addInstructions(
                        0,
                        """
                            const/4 v0, 0x0
                            invoke-virtual { p0 $parameters }, ${it.method}
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
                        "invoke-static { p0 }, $EXTENSION_CLASS->setSettingsController($EXTENSION_SETTING_INTERFACE)V"
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
                            $$"$$EXTENSION_CLASS->setSearchBarOnClickListener(Lcom/google/protobuf/MessageLite;Landroid/view/View$OnClickListener;)V"
                )
            }
        }

        //
        // Toolbar create button upload
        //

        // The commands of the toolbar buttons are extensions, which are parsed only with the extension registry.
        GetGeneratedRegistryFingerprint.method.addInstructions(
            0,
            """
                invoke-static { }, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
                move-result-object v0
                return-object v0
            """
        )

        parseByteArrayWithRegistryMethodRef.get()!!.let { parseMethod ->
            ParseWithRegistryFingerprint.method.addInstructions(
                0,
                """
                    check-cast p0, ${parseMethod.parameterTypes.first()}
                    invoke-static { p0, p1, p2 }, $parseMethod
                    move-result-object p0
                    check-cast p0, Lcom/google/protobuf/MessageLite;
                    return-object p0
                """
            )
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
                        
                        # Replace the create button whose video button has an endpoint the app does not handle.
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS->fixToolbarCreateButtonUpload(Ljava/util/List;)V
                        
                        # Generate Settings Button Bytes (BEFORE modifying list)
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS->createToolbarSettingsButton(Ljava/util/List;)[B
                        move-result-object v$byteRegister
                        
                        # Modify list (removes hidden buttons to free up layout space)
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS->modifyToolbarButtons(Ljava/util/List;)V
                        
                        if-eqz v$byteRegister, :immutable

                        # Parse bytes back into native Buttons wrapper class
                        sget-object v$protoListFreeRegister, $buttonsClass->a:$buttonsClass
                        invoke-static { v$protoListFreeRegister, v$byteRegister }, ${parseByteArrayMethodRef.get()!!}
                        move-result-object v$protoListFreeRegister
                        check-cast v$protoListFreeRegister, $buttonsClass
                        
                        # Append to the list natively
                        invoke-interface { v$protoListRegister, v$protoListFreeRegister }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                        
                        # Move to preferred index
                        invoke-static { v$protoListRegister }, $EXTENSION_CLASS->applyToolbarSettingsButtonIndex(Ljava/util/List;)V

                        :immutable
                        nop
                    """
                )
            }
        }
    }
}

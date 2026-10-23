/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.ask

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.Match
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.FeedFlyoutBufferObjectFingerprint
import app.morphe.patches.youtube.layout.flyout.FullHistoryFlyoutBufferObjectFingerprint
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch;"

private const val EXTENSION_FILTER_CLASS =
    "Lapp/morphe/extension/youtube/patches/components/AskLiveStatusFilter;"

private const val MESSAGE_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$MessageAccess;"
private const val MENU_ITEM_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$MenuItemAccess;"
private const val MENU_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$MenuAccess;"
private const val MENU_ROW_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$MenuRowAccess;"
private const val TAP_HANDLER_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$TapHandlerAccess;"
private const val COMMAND_EXECUTOR_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$CommandExecutorAccess;"
private const val ELEMENTS_RESOURCE_PROCESSOR_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/ask/AskFlyoutMenuPatch$ElementsResourceProcessorAccess;"

private const val EXTENSION_REGISTRY_CLASS = "Lcom/google/protobuf/ExtensionRegistryLite;"

@Suppress("unused")
val askFlyoutMenuPatch = bytecodePatch(
    name = "Ask flyout menu",
    description = "Adds a video menu entry that opens Ask for the video and summarizes the video. " +
            "This patch works with YouTube 21.16.256 and newer.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        flyoutPatch,
        lithoFilterPatch,
        versionCheckPatch,
        authHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // 20.21 registers engagement panels differently,
        // and 20.51 creates the rows of the video menu differently.
        if (!is_21_15_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Ask flyout menu' requires YouTube 21.16+"
            )
        }

        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_ask_summarize_flyout_menu", summary = true)
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Finds the videos of lists that are live or upcoming, before their menu opens.
        addLithoFilter(EXTENSION_FILTER_CLASS)

        // The command executor, which runs a command as if a button sent it.
        val executeCommandMatch = LithoButtonCommandFingerprint.instructionMatches.last()
        val executeCommandMethod = executeCommandMatch.getMethodCalled()
        val commandExecutorClass = executeCommandMethod.definingClass
        val commandClass = executeCommandMethod.parameterTypes.first().toString()

        // GeneratedMessageLite, whose method names are not obfuscated.
        val messageClass = generateSequence(commandClass) { type -> classDefBy(type).superclass }
            .first { type ->
                classDefBy(type).methods.any { method ->
                    method.name == "parseFrom" &&
                            method.parameterTypes.map { it.toString() } ==
                            listOf(type, "[B", EXTENSION_REGISTRY_CLASS)
                }
            }
        val protos = Protos(this, messageClass)

        mutableClassDefBy(messageClass).apply {
            interfaces.add(MESSAGE_ACCESS_INTERFACE)
            addBridge(
                "patch_toByteArray", listOf(), "[B", 2,
                """
                    invoke-virtual { p0 }, $messageClass->toByteArray()[B
                    move-result-object v0
                    return-object v0
                """
            )
            // Any message of a type can be the default instance to parse a message of that type.
            addBridge(
                "patch_parseSameType", listOf("[B"), MESSAGE_ACCESS_INTERFACE, 3,
                """
                    invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
                    move-result-object v0
                    invoke-static { p0, p1, v0 }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
                    move-result-object v0
                    return-object v0
                """
            )
        }

        LithoButtonCommandFingerprint.method.apply {
            val executorRegister = getInstruction<FiveRegisterInstruction>(executeCommandMatch.index).registerC
            addInstruction(
                executeCommandMatch.index,
                "invoke-static { v$executorRegister }, $EXTENSION_CLASS->setCommandExecutor($COMMAND_EXECUTOR_ACCESS_INTERFACE)V"
            )
        }

        // The Ask panel of the opened video is registered from the watch page response.
        // The extension keeps it to build the Ask panel of other videos.
        RegisterEngagementPanelFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onEngagementPanelRegistered($MESSAGE_ACCESS_INTERFACE)V"
        )

        // When the summarize suggestion is not selected, the welcome request of the Ask panel is replaced
        // with the summarize request, and the playback position is removed from the requests of a panel
        // of another video.
        val continuationField = GetPanelRequestDescriptionFingerprint.instructionMatches[3].getFieldReference()
        val formDataField = GetPanelRequestDescriptionFingerprint.instructionMatches[4].getFieldReference()
        GetPanelRequestBuilderFingerprint.method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $continuationField
                invoke-static { v0 }, $EXTENSION_CLASS->onGetPanelRequest(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                iput-object v0, p0, $continuationField
                iget-object v1, p0, $formDataField
                invoke-static { v0, v1 }, $EXTENSION_CLASS->getPanelFormData(Ljava/lang/String;$MESSAGE_ACCESS_INTERFACE)[B
                move-result-object v1
                if-eqz v1, :form_data_unchanged
                ${protos.parse(formDataField.type, bytes = "v1", result = "v0", registry = "v2")}
                check-cast v0, ${formDataField.type}
                iput-object v0, p0, $formDataField
                :form_data_unchanged
                nop
            """
        )

        // The extension runs the tap handler of the summarize suggestion of the opened panel,
        // so the panel shows the suggestion as selected and the answer as loading, as on a tap.
        val tapHandlerMethod = ElementsTapHandlerFingerprint.method
        val tapCommandMethod = ElementsTapHandlerFingerprint.instructionMatches.last().getMethodCalled()

        ElementsTapHandlerConstructorFingerprint.method.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onTapHandlerCreated($TAP_HANDLER_ACCESS_INTERFACE)V"
            )
        }

        mutableClassDefBy(tapHandlerMethod.definingClass).apply {
            val commandHolderField = fields.single { it.type == tapCommandMethod.definingClass }

            interfaces.add(TAP_HANDLER_ACCESS_INTERFACE)
            addBridge(
                "patch_getCommand", listOf(), MESSAGE_ACCESS_INTERFACE, 2,
                """
                    iget-object v0, p0, $commandHolderField
                    invoke-virtual { v0 }, $tapCommandMethod
                    move-result-object v0
                    return-object v0
                """
            )
            addBridge(
                "patch_tap", listOf(), "V", 2,
                """
                    const/4 v0, 0x0
                    invoke-virtual { p0, v0, v0, v0 }, ${tapHandlerMethod.toReference()}
                    return-void
                """
            )
        }

        addSummarizeItemToMenu(protos)

        addElementsResourceLoading(protos)

        // The command executor is an app interface, so its default method is inherited by every executor.
        mutableClassDefBy(commandExecutorClass).apply {
            interfaces.add(COMMAND_EXECUTOR_ACCESS_INTERFACE)
            addBridge(
                "patch_executeCommand", listOf("[B"), "V", 4,
                """
                    ${protos.parse(commandClass, bytes = "p1", result = "v0", registry = "v1")}
                    check-cast v0, $commandClass
                    new-instance v1, Ljava/util/HashMap;
                    invoke-direct { v1 }, Ljava/util/HashMap;-><init>()V
                    invoke-interface { p0, v0, v1 }, $executeCommandMethod
                    return-void
                """,
                accessFlags = AccessFlags.PUBLIC.value
            )
        }
    }
}

/**
 * Parses the app's protobuf messages in smali.
 *
 * @param messageClass GeneratedMessageLite.
 */
private class Protos(private val context: BytecodePatchContext, private val messageClass: String) {
    /**
     * @return Smali that parses the bytes of a register as a message of the type into the result register.
     *         The registry register is overwritten.
     */
    fun parse(type: String, bytes: String, result: String, registry: String) = """
        sget-object $result, ${context.defaultInstanceField(type)}
        invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
        move-result-object $registry
        invoke-static { $result, $bytes, $registry }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
        move-result-object $result
    """
}

/**
 * @return The static field that holds the default instance of a message type.
 */
private fun BytecodePatchContext.defaultInstanceField(type: String) = classDefBy(type).fields.first { field ->
    AccessFlags.STATIC.isSet(field.accessFlags) && field.type == type
}

/**
 * Adds the summarize item to the menu of a video as one of the app's own items, so the menu bottom sheet
 * and the menu popup show it in their own list. The extension updates the row of the item and handles its tap.
 */
private fun BytecodePatchContext.addSummarizeItemToMenu(protos: Protos) {
    val menuFragmentClass = MenuBottomSheetFragmentFactoryFingerprint.classDef
    // The factory takes the menu after the theme.
    val menuClass = MenuBottomSheetFragmentFactoryFingerprint.method.parameterTypes[1].toString()
    val createRowMethod = MenuBottomSheetCreateRowFingerprint.method
    val menuItemClass = createRowMethod.parameterTypes.first().toString()
    val rowTapMatch = MenuBottomSheetRowTapFingerprint.match()
    val rowClass = rowTapMatch.instructionMatches[2].instruction.getReference<TypeReference>()!!.type
    val rowMenuItemField = rowTapMatch.instructionMatches[3].getFieldReference()

    // The menu of a video is shown either as a bottom sheet or as a popup.
    val showMenuMethod = FeedFlyoutBufferObjectFingerprint.method
    val showBottomSheetIndex = showMenuMethod.indexOfFirstInstructionOrThrow(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf(menuClass), returnType = "V")
    )

    // Shows the menu next to the view of its button.
    val showPopupIndex = showMenuMethod.indexOfFirstInstructionOrThrow(
        methodCall(
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
            parameters = listOf(menuClass, "Landroid/view/View;", "Ljava/lang/Object;", "L"),
            returnType = "V"
        )
    )
    val showPopupMethod = showMenuMethod.getInstruction(showPopupIndex).getReference<MethodReference>()!!
    val popupMenu = findPopupMenu(classDefBy(showPopupMethod.definingClass), menuItemClass, rowClass)

    // Adds the item to the menu before the call that shows the menu, and updates the rows of a popup after it.
    fun MutableMethod.addMenuItemBefore(index: Int, isPopup: Boolean) {
        val (callerRegister, menuRegister) = getInstruction(index).registersUsed
        if (isPopup) {
            addInstruction(
                index + 1,
                "invoke-static/range { v$callerRegister .. v$callerRegister }, " +
                        "$EXTENSION_CLASS->onPopupMenuShown($MENU_ACCESS_INTERFACE)V"
            )
        }
        addInstructions(
            index,
            """
                invoke-static/range { v$menuRegister .. v$menuRegister }, $EXTENSION_CLASS->addMenuItem($MESSAGE_ACCESS_INTERFACE)Ljava/lang/Object;
                move-result-object v$menuRegister
                check-cast v$menuRegister, $menuClass
            """
        )
    }

    showMenuMethod.apply {
        // The popup is shown before or after the bottom sheet, depending on the app version,
        // so the later call is changed first.
        listOf(showBottomSheetIndex, showPopupIndex).sortedDescending().forEach { index ->
            addMenuItemBefore(index, index == showPopupIndex)
        }
    }

    // Lists that are not built with Litho, such as the History page, show the popup from the menu button
    // with the same call.
    FullHistoryFlyoutBufferObjectFingerprint.method.apply {
        addMenuItemBefore(indexOfFirstInstructionOrThrow(methodCall(reference = showPopupMethod)), true)
    }

    mutableClassDefBy(menuItemClass).interfaces.add(MENU_ITEM_ACCESS_INTERFACE)

    mutableClassDefBy(rowClass).apply {
        interfaces.add(MENU_ROW_ACCESS_INTERFACE)
        addBridge(
            "patch_getMenuItem", listOf(), MENU_ITEM_ACCESS_INTERFACE, 2,
            """
                iget-object v0, p0, $rowMenuItemField
                return-object v0
            """
        )
    }

    val menus = listOf(
        MenuRows(menuFragmentClass.type, createRowMethod, findRowsField(menuFragmentClass), null),
        MenuRows(popupMenu.type, popupMenu.createRowMethod, popupMenu.rowsField, popupMenu.dismissMethod)
    )
    for (menu in menus) {
        mutableClassDefBy(menu.type).apply {
            interfaces.add(MENU_ACCESS_INTERFACE)
            addBridge(
                "patch_getRows", listOf(), "Ljava/util/List;", 2,
                """
                    iget-object v0, p0, ${menu.rowsField}
                    return-object v0
                """
            )
            addBridge(
                "patch_createRow", listOf(MENU_ITEM_ACCESS_INTERFACE), "Ljava/lang/Object;", 4,
                """
                    check-cast p1, $menuItemClass
                    invoke-virtual { p0, p1 }, ${menu.createRowMethod.toReference()}
                    move-result-object v0
                    const/4 v1, 0x0
                    invoke-virtual { v0, v1 }, ${menu.createRowMethod.returnType}->orElse(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                """
            )
            addBridge(
                "patch_dismissPopup", listOf(), "Z", 2,
                if (menu.dismissMethod == null) {
                    """
                        const/4 v0, 0x0
                        return v0
                    """
                } else {
                    """
                        invoke-virtual { p0 }, ${menu.dismissMethod.toReference()}
                        const/4 v0, 0x1
                        return v0
                    """
                }
            )

            findMutableMethodOf(menu.createRowMethod).addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onMenuRowCreated($MENU_ACCESS_INTERFACE$MENU_ITEM_ACCESS_INTERFACE)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $menuItemClass
                """
            )
        }
    }

    // The bottom sheet handles the taps on its rows itself, and the popup with a tap handler of its rows.
    for (rowTapMethod in listOf(rowTapMatch.method, popupMenu.rowTapMethod)) {
        mutableClassDefBy(rowTapMethod.definingClass).findMutableMethodOf(rowTapMethod).addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onMenuRowTapped(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :not_summarize_item
                return-void
                :not_summarize_item
                nop
            """
        )
    }

    // A popup that shows the menu items as they are, such as on tablets, shows them with a presenter
    // that handles their taps. The presenter dismisses the popup and reads the menu item it shows.
    val itemTapMatch = Fingerprint(
        name = "onClick",
        returnType = "V",
        parameters = listOf("Landroid/view/View;"),
        filters = listOf(
            methodCall(reference = popupMenu.dismissMethod),
            fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
            checkCast(menuItemClass, location = MatchAfterImmediately())
        )
    ).match()
    val itemField = itemTapMatch.instructionMatches[1].getFieldReference()
    itemTapMatch.method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $itemField
            invoke-static/range { v0 .. v0 }, $EXTENSION_CLASS->onMenuItemTapped(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :not_summarize_item
            return-void
            :not_summarize_item
            nop
        """
    )

    restoreMenuRowTextConstraints()
}

/**
 * A menu that creates the rows of its menu items, either the bottom sheet or the popup.
 *
 * @param dismissMethod Dismisses the popup, or null for the bottom sheet.
 */
private class MenuRows(
    val type: String,
    val createRowMethod: Method,
    val rowsField: FieldReference,
    val dismissMethod: Method?,
)

/**
 * The popup that shows the menu of a video, which creates the rows of the menu items
 * and handles the taps on the rows with its own methods.
 */
private class PopupMenu(
    val type: String,
    val createRowMethod: Method,
    val rowsField: FieldReference,
    val dismissMethod: Method,
    val rowTapMethod: Method,
)

private fun BytecodePatchContext.findPopupMenu(
    popupClass: ClassDef,
    menuItemClass: String,
    rowClass: String,
): PopupMenu {
    val createRowMatch = PopupMenuCreateRowFingerprint.match(popupClass)
    val createRowMethod = createRowMatch.method
    check(createRowMethod.parameterTypes.first().toString() == menuItemClass) {
        "Unexpected popup row creation method: $createRowMethod"
    }
    // The popup creates the tap handler of each row with the row.
    val rowTapMethod = Fingerprint(
        definingClass = createRowMatch.instructionMatches.first().instruction.getReference<TypeReference>()!!.type,
        returnType = "V",
        parameters = listOf(rowClass)
    ).method

    return PopupMenu(
        popupClass.type,
        createRowMethod,
        findRowsField(popupClass),
        PopupMenuDismissFingerprint.match(popupClass).method,
        rowTapMethod
    )
}

/**
 * @return The field of a menu with the list of its rows.
 */
private fun BytecodePatchContext.findRowsField(menuClass: ClassDef) = menuClass.fields.single { field ->
    !AccessFlags.STATIC.isSet(field.accessFlags) &&
            generateSequence(field.type) { type -> classDefByOrNull(type)?.superclass }
                .any { type -> type == "Ljava/util/AbstractList;" }
}

/**
 * The Ask chat shows nothing until a watch page response attaches its Elements templates.
 * Lets the extension load the Elements resources of a watch page it fetches,
 * with the processor that loads them from the app's own responses.
 */
private fun BytecodePatchContext.addElementsResourceLoading(protos: Protos) {
    val processorClass = ElementsFrameworkUpdatesFingerprint.classDef
    val frameworkUpdatesClass = ElementsFrameworkUpdatesReaderFingerprint.method.parameterTypes.first().toString()
    // Takes the account of the response, which the processor does not use for Elements resources.
    val processMethod = Fingerprint(
        definingClass = processorClass.type,
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf("L", frameworkUpdatesClass)
    ).method
    val usedResourcesField = processorClass.fields.single { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == "Ljava/util/Set;"
    }

    ElementsResourceProcessorConstructorFingerprint.method.apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onElementsResourceProcessorCreated($ELEMENTS_RESOURCE_PROCESSOR_ACCESS_INTERFACE)V"
            )
        }
    }

    mutableClassDefBy(processorClass.type).apply {
        interfaces.add(ELEMENTS_RESOURCE_PROCESSOR_ACCESS_INTERFACE)
        addBridge(
            "patch_processFrameworkUpdates", listOf("[B"), "V", 4,
            """
                ${protos.parse(frameworkUpdatesClass, bytes = "p1", result = "v0", registry = "v1")}
                check-cast v0, $frameworkUpdatesClass
                const/4 v1, 0x0
                invoke-virtual { p0, v1, v0 }, ${processMethod.toReference()}
                return-void
            """
        )
        addBridge(
            "patch_getUsedResources", listOf(), "Ljava/util/Set;", 2,
            """
                iget-object v0, p0, $usedResourcesField
                return-object v0
            """
        )
    }
}

/**
 * The menu bottom sheet reuses its rows. A row without icons, such as the loading download item,
 * gets start constraints that move its text to the place of the icon, and keeps them when it is reused,
 * such as for the summarize item that replaces its loading item, which then shows its icon over its text.
 * Restores the start constraints of an inflated row before each bind.
 * Newer app versions restore them for rows with icons themselves, some only behind a flag.
 */
private fun BytecodePatchContext.restoreMenuRowTextConstraints() {
    MenuRowBindFingerprint.let {
        val textContainerField = it.instructionMatches[1].getFieldReference()
        val constraintsClass = it.instructionMatches[5].instruction.getReference<TypeReference>()!!.type
        val startConstraintFields = it.instructionMatches.takeLast(2).map { match -> match.getFieldReference() }

        // -1 is unset, as the layout of the row does not set them.
        it.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $textContainerField
                if-eqz v0, :no_text_container
                invoke-virtual { v0 }, Landroid/widget/LinearLayout;->getLayoutParams()Landroid/view/ViewGroup${'$'}LayoutParams;
                move-result-object v1
                check-cast v1, $constraintsClass
                const/4 v2, -0x1
                iput v2, v1, ${startConstraintFields[0]}
                iput v2, v1, ${startConstraintFields[1]}
                invoke-virtual { v0, v1 }, Landroid/widget/LinearLayout;->setLayoutParams(Landroid/view/ViewGroup${'$'}LayoutParams;)V
                :no_text_container
                nop
            """
        )
    }
}

private fun MethodReference.toReference() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * @return The field that the matched instruction accesses, which can be declared in a superclass.
 */
private fun Match.InstructionMatch.getFieldReference() = instruction.getReference<FieldReference>()!!

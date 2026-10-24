/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.ask

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Registers an engagement panel, such as the Ask panel of the video that is opened,
 * so a command can show it by its id.
 * The panel is the first parameter.
 */
internal object RegisterEngagementPanelFingerprint : Fingerprint(
    classFingerprint = EngagementPanelControllerFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "Z", "L", "Z", "L")
)

/**
 * Describes a get_panel request, which loads an engagement panel or a turn of its chat,
 * such as a turn of the Ask chat.
 * The fourth match reads the continuation of the request, and the fifth reads its form data.
 */
internal object GetPanelRequestDescriptionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("params"),
        string("panelId"),
        string("continuation"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(1)),
        string("formData", location = MatchAfterImmediately()),
        string("query")
    )
)

/**
 * Builds the message of a get_panel request.
 */
internal object GetPanelRequestBuilderFingerprint : Fingerprint(
    classFingerprint = GetPanelRequestDescriptionFingerprint,
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z")
    )
)

/**
 * Creates the menu bottom sheet with the menu in its arguments.
 * The older menu bottom sheets that read the same argument have no such static factory.
 */
internal object MenuBottomSheetFragmentFactoryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        string("MENU_BOTTOM_SHEET_FRAGMENT_KEY")
    )
)

/**
 * Creates the row of a menu item of the menu bottom sheet. The parameter is the menu item.
 * It is protected in some targets, such as 21.21 and 21.30.
 */
internal object MenuBottomSheetCreateRowFingerprint : Fingerprint(
    classFingerprint = MenuBottomSheetFragmentFactoryFingerprint,
    returnType = "Lj\$/util/Optional;",
    parameters = listOf("L")
)

/**
 * Handles a tap on a row of the menu bottom sheet.
 * The cast is to the class of the rows of menu items, and the field read after it is the menu item of the row.
 */
internal object MenuBottomSheetRowTapFingerprint : Fingerprint(
    classFingerprint = MenuBottomSheetFragmentFactoryFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(opcode = Opcode.INSTANCE_OF, location = MatchFirst()),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * Creates the row of a menu item of the popup video menu. Matched against the popup class.
 * The first match creates the tap handler of the row.
 */
internal object PopupMenuCreateRowFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lj\$/util/Optional;",
    parameters = listOf("L"),
    filters = listOf(
        opcode(opcode = Opcode.NEW_INSTANCE),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("Ljava/lang/Object;", "I"),
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Dismisses the popup video menu. Matched against the popup class.
 */
internal object PopupMenuDismissFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf()
)

/**
 * Loads the Elements resources, such as templates, that the framework updates of a response attach.
 * One parameter is the framework updates. In newer app versions, this is not the method of the processor
 * that other code calls with the framework updates, but a method that it calls.
 */
internal object ElementsFrameworkUpdatesFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L"),
    filters = listOf(
        string("fut elements"),
        string("Failed to process FrameworkUpdateTransport")
    )
)

/**
 * Binds a menu item to a row of the menu bottom sheet, which can be a reused row.
 * The matches from the second are the read of the text container of the row and of its constraints,
 * and the writes of the start constraints that a row without icons and detail text gets.
 */
internal object MenuRowBindFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "bottom_sheet_list_item_height"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/widget/LinearLayout;"),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(
            smali = "Landroid/widget/LinearLayout;->getLayoutParams()Landroid/view/ViewGroup\$LayoutParams;",
            location = MatchAfterImmediately()
        ),
        opcode(opcode = Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        // Newer app versions check the icons after the cast.
        opcode(opcode = Opcode.IPUT, location = MatchAfterWithin(8)),
        opcode(opcode = Opcode.IPUT, location = MatchAfterImmediately())
    )
)

/**
 * Reads the Elements updates from the framework updates of a response.
 * The parameter is the framework updates.
 */
internal object ElementsFrameworkUpdatesReaderFingerprint : Fingerprint(
    classFingerprint = ElementsFrameworkUpdatesFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L")
)

internal object ElementsResourceProcessorConstructorFingerprint : Fingerprint(
    classFingerprint = ElementsFrameworkUpdatesFingerprint,
    name = "<init>"
)

/**
 * Runs the command of a tapped Elements component, such as a suggestion of Ask.
 * The last instruction gets the command from the command holder of the component.
 */
internal object ElementsTapHandlerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L", "Landroid/view/MotionEvent;"),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->getLocationInWindow([I)V"),
        methodCall(
            returnType = "Lcom/google/protos/youtube/elements/CommandOuterClass\$Command;",
            parameters = listOf()
        )
    )
)

/**
 * Creates the tap handler of an Elements component, with the command holder of the component.
 */
internal object ElementsTapHandlerConstructorFingerprint : Fingerprint(
    classFingerprint = ElementsTapHandlerFingerprint,
    name = "<init>"
)

/**
 * Sends the command of a tapped Litho button to the command executor.
 * The last instruction is the call of the command executor.
 */
internal object LithoButtonCommandFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(opcode = Opcode.NEW_INSTANCE),
        opcode(opcode = Opcode.INVOKE_DIRECT, location = MatchAfterImmediately()),
        newInstance(type = "Ljava/util/HashMap;", location = MatchAfterWithin(5)),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            smali = "Ljava/util/HashMap;-><init>(Ljava/util/Map;)V",
            location = MatchAfterWithin(5)
        ),
        string("command_status_callback", location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L", "Ljava/util/Map;"),
            returnType = "V"
        )
    )
)

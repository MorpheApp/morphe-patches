/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FeedBottomSheetFlyoutFingerprint : Fingerprint (
    classFingerprint = Fingerprint(
        parameters = listOf("Landroid/os/Bundle;"),
        filters = listOf(
            string("BaseBottomSheetDialogFragment.useNewUi"),
            string("BaseBottomSheetDialogFragment.peekHeightEnabled"),
            string("BaseBottomSheetDialogFragment.largeFormWidthDp"),
        )
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Landroid/app/Dialog;",
    parameters = listOf("Landroid/os/Bundle;")
)

internal object FeedPopupWindowFlyoutFingerprint : Fingerprint (
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Landroid/widget/PopupWindow;->setOnDismissListener(Landroid/widget/PopupWindow$OnDismissListener;)V",
        )
    )
)

internal object FeedFlyoutBufferObjectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf(
        "com.google.android.libraries.youtube.rendering.elements.sender_view",
        "com.google.android.libraries.youtube.innertube.endpoint.tag",
        "com.google.android.libraries.youtube.innertube.bundle",
        "com.google.android.libraries.youtube.logging.interaction_logger"
    )
)

internal object FullHistoryFlyoutBufferObjectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "innertube_menu_anchor_model"),
        resourceLiteral(ResourceType.ID, "innertube_menu_anchor_tag"),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        resourceLiteral(ResourceType.ID, "innertube_menu_anchor_interaction_logger"),
    ),
    custom = { method, _ ->
        method.name == "onClick"
    }
)

internal object FeedFlyoutButtonsInitializerFingerprint : Fingerprint(
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_STATIC, returnType = "Ljava/lang/CharSequence;", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ),
        opcode(Opcode.AND_INT_2ADDR, location = MatchAfterWithin(5)),
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterWithin(7)),
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("I"), location = MatchAfterWithin(3)),
        methodCall(opcode = Opcode.INVOKE_DIRECT, name = "<init>"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/Runnable;"),
    ),
    strings = listOf(
        "ElementTransformer cannot be null",
        "Text missing for BottomSheetMenuItem.",
        "Text missing for BottomSheetMenuItem with iconType: ",
    )
)

internal object FeedFlyoutButtonsInitializerOnItemClickFingerprint : Fingerprint(
    classFingerprint = FeedFlyoutButtonsInitializerFingerprint,
    name = "onItemClick"
)

internal object InteractiveStickerRendererGetEditViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        string("getEditView called without setting interactiveStickerRenderer"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "[B") // The only byte array accessed in the method.
    )
)

internal object FlyoutMenuItemMessageFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/protobuf/MessageLite;"),
    filters = listOf(
        literal(42357),
        opcode(Opcode.INSTANCE_OF, location = MatchAfterWithin(10)),
        string("downloads_page_downloads_item_section_identifier")
    )
)

internal object SingularGeneratedExtensionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.CONSTRUCTOR, AccessFlags.STATIC),
    filters = listOf(
        methodCall(name = "registerDefaultInstance"),
        fieldAccess(opcode = Opcode.SGET_OBJECT, type = "L", location = MatchAfterWithin(2)),
        string(""),
        literal(125983101),
        methodCall(name = "newSingularGeneratedExtension")
    )
)


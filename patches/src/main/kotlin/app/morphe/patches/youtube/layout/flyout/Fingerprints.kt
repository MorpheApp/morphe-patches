/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FlyoutBufferDisablerLiteralFingerprint : Fingerprint(
    parameters = listOf(),
    returnType = "Z",
    filters = listOf(
        literal(45386415L)
    )
)

internal object FeedFlyoutDialogFingerprint : Fingerprint (
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Landroid/app/Dialog;",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        opcode(opcode = Opcode.INVOKE_VIRTUAL),
        opcode(opcode = Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IPUT_BOOLEAN),
        opcode(opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.RETURN_OBJECT, location = MatchAfterImmediately()),
    ),
)

// This could be more precise, but is difficult to fingerprint.
internal object FeedFlyoutButtonsContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Landroid/view/View;", "Ljava/lang/Object;", "L"),
)

internal object FeedFlyoutButtonsInitializerFingerprint : Fingerprint(
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_STATIC, returnType = "Ljava/lang/CharSequence;", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CONST_4),
        opcode(Opcode.IF_NEZ),
        opcode(Opcode.AND_INT_2ADDR, location = MatchAfterWithin(3)),
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterWithin(4)),
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("I"), location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_DIRECT, name = "<init>"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/Runnable;"),
    ),
    strings = listOf(
        "ElementTransformer cannot be null",
        "Text missing for BottomSheetMenuItem.",
        "Text missing for BottomSheetMenuItem with iconType: ",
    )
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

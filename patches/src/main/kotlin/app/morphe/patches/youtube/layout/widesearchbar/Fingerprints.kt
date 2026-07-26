/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.widesearchbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.youtube.layout.hide.general.YouTubeDoodlesImageViewFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ActionbarRingoViewFingerprint : Fingerprint(
    classFingerprint = YouTubeDoodlesImageViewFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/widget/ImageView;->setContentDescription(Ljava/lang/CharSequence;)V",
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Landroid/widget/ImageView;->setOnClickListener(Landroid/view/View$OnClickListener;)V",
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/widget/ImageView;->setClickable(Z)V",
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

internal object MobileTopBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/MenuItem;", "Landroid/content/Context;"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "mobile_topbar_button_item"),
        methodCall("Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),

        resourceLiteral(ResourceType.ID, "menu_item_view"),
        methodCall("Landroid/view/View;->findViewById(I)Landroid/view/View;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        checkCast("Landroid/widget/ImageView;")
    )
)

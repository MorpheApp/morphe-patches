/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.widesearchbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
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

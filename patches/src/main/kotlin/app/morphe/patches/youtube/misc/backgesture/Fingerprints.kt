/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3416
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.backgesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object YouTubeMainActivityOnBackPressedFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onBackPressed",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_SUPER,
            name = "onBackPressed"
        ),
        opcode(Opcode.RETURN_VOID)
    )
)

internal object PredictiveGesturesOnBackInvokedFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        name = "onBackCancelled",
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf(),
        filters = listOf(
            literal(0),
            opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
            fieldAccess(
                opcode = Opcode.IGET_OBJECT,
                type = "Ljava/lang/Object;",
                location = MatchAfterWithin(5)
            ),
            literal(-1, location = MatchAfterWithin(10)),
        )
    ),
    name = "onBackInvoked"
)

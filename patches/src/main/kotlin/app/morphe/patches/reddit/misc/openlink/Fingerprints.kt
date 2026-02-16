/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object CustomReportsFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/safety/report/dialogs/customreports/",
    returnType = "V",
    filters = listOf(
        string("https://www.crisistextline.org/"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;"
        ),
        opcode(Opcode.CHECK_CAST),
        methodCall(returnType = "V"),
        opcode(
            opcode = Opcode.RETURN_VOID,
            location = MatchAfterImmediately()
        ),
    )
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ArticleConstructorFingerprint : Fingerprint(
    classFingerprint = ArticleToStringFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V"
)

private object ArticleToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("Article(postId=")
    )
)

internal object CustomReportsFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("https://www.crisistextline.org/"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;"
        ),
        methodCall(returnType = "V"),
        opcode(
            opcode = Opcode.RETURN_VOID,
            location = MatchAfterImmediately()
        )
    )
)

internal object FbpActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/fullbleedplayer/common/FbpActivity;",
    name = "onCreate",
    returnType = "V"
)

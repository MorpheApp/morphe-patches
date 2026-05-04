/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.subredditdialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FrequentUpdatesHandlerFingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/screens/pager/FrequentUpdatesHandler$handleFrequentUpdates$",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Z"
        ),
        opcode(
            Opcode.MOVE_RESULT,
            location = MatchAfterImmediately()
        ),
        opcode(
            Opcode.IF_NEZ,
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Lcom/reddit/domain/model/Subreddit;->getUserIsSubscriber()Ljava/lang/Boolean;",
            location = MatchAfterWithin(3)
        )
    )
)

internal object NSFWAlertEmitFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            // Many classes have a method named getOver18()
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                smali = "Lcom/reddit/domain/model/Subreddit;->getOver18()Ljava/lang/Boolean;"
            ),
            methodCall( // 2026.17.0+
                opcode = Opcode.INVOKE_VIRTUAL,
                smali = "Lcom/reddit/domain/model/UserSubreddit;->getOver18()Ljava/lang/Boolean;"
            )
        ),
        anyInstruction(
            // Many classes have a method named getHasBeenVisited()
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                smali = "Lcom/reddit/domain/model/Subreddit;->getHasBeenVisited()Z"
            ),
            methodCall( // 2026.17.0+
                opcode = Opcode.INVOKE_VIRTUAL,
                smali = "Lcom/reddit/domain/model/UserSubreddit;->getHasBeenVisited()Z"
            )
        ),
        opcode(
            Opcode.IF_NEZ,
            location = MatchAfterWithin(8)
        ),
        string("nsfwAlertDelegate"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Lcom/reddit/session/Session;->isIncognito()Z"
        )
    )
)

private object NSFWAlertDialogClassFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("NsfwAlertDialogScreenDelegate")
    )
)

internal object NSFWAlertShowDialogFingerprint : Fingerprint(
    classFingerprint = NSFWAlertDialogClassFingerprint,
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "L",
            parameters = listOf(
                "Landroid/content/Context;",
                $$"Landroid/content/DialogInterface$OnClickListener;",
                $$"Landroid/content/DialogInterface$OnClickListener;"
            )
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "L",
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        newInstance(
            type = "Ljava/lang/ref/WeakReference;",
            location = MatchAfterWithin(5)
        )
    )
)

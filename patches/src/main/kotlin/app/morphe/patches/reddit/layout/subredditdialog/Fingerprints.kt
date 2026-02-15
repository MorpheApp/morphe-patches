package app.morphe.patches.reddit.layout.subredditdialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private val SHOW_DIALOG_FILTERS = listOf(
    methodCall(
        opcode = Opcode.INVOKE_VIRTUAL,
        name = "show"
    ),
    opcode(
        Opcode.MOVE_RESULT_OBJECT,
        location = MatchAfterImmediately()
    ),
)

internal object FrequentUpdatesHandlerFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/screens/pager/FrequentUpdatesHandler\$handleFrequentUpdates$",
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
    definingClass = "Lcom/reddit/screens/pager/v2/",
    name = "emit",
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Lcom/reddit/domain/model/Subreddit;->getOver18()Ljava/lang/Boolean;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Lcom/reddit/domain/model/Subreddit;->getHasBeenVisited()Z"
        ),
        opcode(
            Opcode.IF_NEZ,
            location = MatchAfterWithin(3)
        ),
        string("nsfwAlertDelegate"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Lcom/reddit/session/Session;->isIncognito()Z"
        ),
    )
)

internal object NSFWAlertDialogBuilderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    filters = SHOW_DIALOG_FILTERS
)

internal object NSFWAlertDialogInstanceFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = SHOW_DIALOG_FILTERS
)

internal object NSFWAlertDialogParentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("NsfwAlertDialogScreenDelegate")
    )
)

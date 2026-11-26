/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5193
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.downloadall

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val ACTION_SHEET_ARGS_CLASS = $$"Lcom/reddit/sharing/actions/ActionSheet$Args;"

/**
 * Loads the post shown in the share sheet.
 */
internal object ShareSheetLoadLinkFingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/sharing/actions/handler/ActionsScreenEventHandler$loadLink$1;",
    name = "invokeSuspend",
    filters = listOf(
        checkCast("Lcom/reddit/domain/model/Link;"),
        methodCall(
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "getPromoted",
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * Handles a share sheet action. The Download action launches the download of the item in view.
 */
internal object ShareActionDownloadMediaFingerprint : Fingerprint(
    filters = listOf(
        newInstance($$"Lcom/reddit/sharing/actions/handler/ActionsScreenShareActionHandler$downloadMedia$1;"),
        methodCall(
            definingClass = "this",
            returnType = "V",
            parameters = listOf("Lkotlin/jvm/functions/Function1;"),
            location = MatchAfterWithin(8)
        )
    )
)

internal object ShareActionHandlerConstructorFingerprint : Fingerprint(
    classFingerprint = ShareActionDownloadMediaFingerprint,
    name = "<init>",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = ACTION_SHEET_ARGS_CLASS
        )
    )
)

/**
 * Returns the id of the shared post, or null if the share sheet is not for a post.
 */
internal object ShareSheetPostIdFingerprint : Fingerprint(
    classFingerprint = ShareActionDownloadMediaFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(ACTION_SHEET_ARGS_CLASS),
    filters = listOf(
        checkCast($$"Lcom/reddit/sharing/custom/ShareableData$ShareablePostData;")
    )
)

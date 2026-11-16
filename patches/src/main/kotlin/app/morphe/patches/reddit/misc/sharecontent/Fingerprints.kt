/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4916
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.sharecontent

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object SharePostSubjectFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lcom/reddit/sharing/custom/ShareableData;",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    ),
    filters = listOf(
        checkCast("Lcom/reddit/domain/model/Link;"),
        methodCall(
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "getSubredditNamePrefixed"
        ),
        string("] ")
    )
)

internal object SharePostAppTargetFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "L",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/reddit/sharing/analytics/OsChooserShareContext;",
            name = "<init>"
        ),
        methodCall(
            parameters = listOf(
                "L",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Landroid/net/Uri;",
                "Lcom/reddit/sharing/analytics/OsChooserShareContext;"
            ),
            returnType = "Landroid/content/Intent;"
        ),
        methodCall(
            parameters = listOf(
                "Landroid/content/Context;",
                "Landroid/content/Intent;",
                "Ljava/lang/String;"
            ),
            returnType = "V"
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            type = "Lkotlin/Unit;",
            location = MatchAfterImmediately()
        )
    )
)

internal object SharePostAppTargetLegacyFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "L",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/reddit/sharing/analytics/OsChooserShareContext;",
            name = "<init>"
        ),
        methodCall(
            parameters = listOf(
                "L",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Landroid/net/Uri;",
                "Lcom/reddit/sharing/analytics/OsChooserShareContext;"
            ),
            returnType = "Landroid/content/Intent;"
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "startActivity",
            parameters = listOf("Landroid/content/Intent;"),
            returnType = "V"
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            type = "Lkotlin/Unit;",
            location = MatchAfterImmediately()
        )
    )
)

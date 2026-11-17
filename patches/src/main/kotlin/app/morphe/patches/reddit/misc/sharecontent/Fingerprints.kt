/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4916
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.sharecontent

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Builds the share subject of a share sheet handler. Loads the shared post.
 * Before 2026.44.0 the share sheet of the "shareSheetOverhaulM2" experiment
 * has its own handler, so this matches each handler.
 */
internal object SharePostSubjectFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        checkCast("Lcom/reddit/domain/model/Link;"),
        methodCall(
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "getSubredditNamePrefixed"
        ),
        string("] ")
    )
)

/**
 * Opens "Share via..." or an app shortcut of the share sheet.
 * Matched in the class of [SharePostSubjectFingerprint].
 * 2026.44.0+
 */
internal object SharePostAppTargetFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        methodCall(
            returnType = "Landroid/content/Intent;"
        ),
        // Share launcher.
        methodCall(
            parameters = listOf(
                "Landroid/content/Context;",
                "Landroid/content/Intent;",
                "Ljava/lang/String;"
            ),
            returnType = "V"
        )
    )
)

/**
 * Opens "Share via..." or an app shortcut of the share sheet.
 * Matched in the class of [SharePostSubjectFingerprint].
 * Before 2026.44.0
 */
internal object SharePostAppTargetLegacyFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        methodCall(
            returnType = "Landroid/content/Intent;"
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "startActivity",
            parameters = listOf("Landroid/content/Intent;")
        )
    )
)

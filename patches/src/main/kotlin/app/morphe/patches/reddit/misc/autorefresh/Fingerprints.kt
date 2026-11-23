/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.autorefresh

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance

/**
 * Creates and posts the event that triggers an automatic feed reload on returning to the app.
 */
internal object AutoRefreshFeedFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        newInstance("Lcom/reddit/feeds/ui/events/OnAutoRefreshFeed;"),
        methodCall(
            definingClass = "Lcom/reddit/feeds/ui/events/OnAutoRefreshFeed;",
            name = "<init>",
            location = MatchAfterImmediately()
        ),
        methodCall(
            name = "onEvent",
            location = MatchAfterImmediately()
        )
    )
)

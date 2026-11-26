/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5244
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.autorefresh

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance

private const val ON_AUTO_REFRESH_FEED_CLASS = "Lcom/reddit/feeds/ui/events/OnAutoRefreshFeed;"

/**
 * Runs when the home or following feed is shown again. If the app was in the background
 * longer than a server set timeout, it sends the event that reloads the feed and scrolls it to the top.
 */
internal object AutoRefreshFeedFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        newInstance(ON_AUTO_REFRESH_FEED_CLASS),
        methodCall(
            definingClass = ON_AUTO_REFRESH_FEED_CLASS,
            name = "<init>"
        ),
        methodCall(name = "onEvent")
    )
)

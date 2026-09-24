/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.Fingerprint

/** Reddit 2026.37.0 common feed-element processor. */
internal object FeedElementProcessorFingerprint : Fingerprint(
    definingClass = "Lqf80;",
    name = "b",
    returnType = "Lkotlin/Pair;",
    parameters = listOf(
        "Lcom/reddit/feeds/data/FeedType;",
        "Ljava/util/List;",
        "Ljava/util/List;"
    )
)

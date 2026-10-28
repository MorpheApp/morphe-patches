/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.padding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal

/**
 * Changes how the height of the navigation bar and the miniplayer is given to the pages,
 * which leaves wrong padding in some pages, such as the incognito search.
 * 21.29 and older read it with a single accessor method.
 */
internal object IncognitoSearchPaddingFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45724388L)
    )
)

/**
 * Lays out the feed behind the navigation bar instead of above it, which triggers
 * dynamic runtime insets/padding recalculations on the RecyclerView that cause watch history
 * items (and other top horizontal carousels) to briefly detach and vanish (#3135).
 */
internal object FeedBehindNavigationBarFeatureFlagsFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            literal(45631257L),
            literal(45708229L)
        )
    )
)

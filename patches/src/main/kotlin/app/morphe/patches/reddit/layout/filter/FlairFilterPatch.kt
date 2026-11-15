/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.filter

import app.morphe.patcher.patch.patch

@Suppress("unused")
val flairFilterPatch = patch(
    name = "Flair filters",
    description = "Adds per-community post-flair filters and an observed-flair picker under Morphe > Filters. " +
        "This patch supports Reddit 2026.45.0.",
    default = false
) {
    compatibleWith(contentFilterCompatibility)
    dependsOn(contentFilterHooks)
    execute { registerContentFilter("Lapp/morphe/extension/reddit/patches/FlairFilterPatch;") }
}

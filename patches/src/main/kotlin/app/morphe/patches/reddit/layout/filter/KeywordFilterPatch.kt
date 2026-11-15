/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.filter

import app.morphe.patcher.patch.patch

@Suppress("unused")
val keywordFilterPatch = patch(
    name = "Keyword filters",
    description = "Adds case-insensitive regular-expression filters for post titles and descriptions under Morphe > Filters. " +
        "This patch supports Reddit 2026.45.0."
) {
    compatibleWith(contentFilterCompatibility)
    dependsOn(contentFilterHooks)
    execute { registerContentFilter("Lapp/morphe/extension/reddit/patches/KeywordFilterPatch;") }
}

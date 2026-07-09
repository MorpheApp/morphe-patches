/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.spans;

import app.morphe.extension.shared.StringTrieSearch;

public final class StringSpanFilterGroupList extends SpanFilterGroupList<String, StringSpanFilterGroup> {
    protected StringTrieSearch createSearchGraph() {
        return new StringTrieSearch();
    }
}

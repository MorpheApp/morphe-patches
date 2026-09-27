/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.components;

import app.morphe.extension.shared.ByteTrieSearch;

/**
 * If searching for a single byte pattern, then it is slightly better to use
 * {@link ByteArrayFilterGroup#check(byte[])} as it uses BMH which is faster
 * than a prefix tree to search for only 1 pattern.
 */
public class ByteArrayFilterGroupList extends FilterGroupList<byte[], ByteArrayFilterGroup> {
    protected ByteTrieSearch createSearchGraph() {
        return new ByteTrieSearch();
    }
}

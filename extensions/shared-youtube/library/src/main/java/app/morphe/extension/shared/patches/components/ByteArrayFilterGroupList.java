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

package app.morphe.extension.shared.patches.components;

import app.morphe.extension.shared.StringTrieSearch;

public class StringFilterGroupList extends FilterGroupList<String, CharSequence, StringFilterGroup> {
    protected StringTrieSearch createSearchGraph() {
        return new StringTrieSearch();
    }
}

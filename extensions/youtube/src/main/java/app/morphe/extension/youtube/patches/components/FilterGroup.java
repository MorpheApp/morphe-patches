package app.morphe.extension.youtube.patches.components;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;

abstract class FilterGroup<T> {
    final static class FilterGroupResult {
        private BooleanSetting setting;
        private int matchedIndex;
        private int matchedLength;
        // In the future it might be useful to include which pattern matched,
        // but for now that is not needed.

        FilterGroupResult() {
            this(null, -1, 0);
        }

        FilterGroupResult(BooleanSetting setting, int matchedIndex, int matchedLength) {
            setValues(setting, matchedIndex, matchedLength);
        }

        public void setValues(BooleanSetting setting, int matchedIndex, int matchedLength) {
            this.setting = setting;
            this.matchedIndex = matchedIndex;
            this.matchedLength = matchedLength;
        }

        /**
         * A null value if the group has no setting,
         * or if no match is returned from {@link FilterGroupList#check(Object)}.
         */
        public BooleanSetting getSetting() {
            return setting;
        }

        public boolean isFiltered() {
            return matchedIndex >= 0;
        }

        /**
         * Matched index of first pattern that matched, or -1 if nothing matched.
         */
        public int getMatchedIndex() {
            return matchedIndex;
        }

        /**
         * Length of the matched filter pattern.
         */
        public int getMatchedLength() {
            return matchedLength;
        }
    }

    protected final BooleanSetting setting;
    protected final T[] filters;

    /**
     * Initialize a new filter group.
     *
     * @param setting The associated setting.
     * @param filters The filters.
     */
    @SafeVarargs
    public FilterGroup(final BooleanSetting setting, final T... filters) {
        this.setting = setting;
        this.filters = filters;
        if (filters.length == 0) {
            throw new IllegalArgumentException("Must use one or more filter patterns (zero specified)");
        }
    }

    public boolean isEnabled() {
        return setting == null || setting.get();
    }

    /**
     * @return If {@link FilterGroupList} should include this group when searching.
     * By default, all filters are included except non enabled settings that require reboot.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean includeInSearch() {
        return isEnabled() || !setting.rebootApp;
    }

    @NonNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + (setting == null ? "(null setting)" : setting);
    }

    public abstract FilterGroupResult check(final T stack);
}

class StringFilterGroup extends FilterGroup<String> {

    public StringFilterGroup(final BooleanSetting setting, final String... filters) {
        super(setting, filters);
    }

    @Override
    public FilterGroupResult check(final String string) {
        int matchedIndex = -1;
        int matchedLength = 0;
        if (isEnabled()) {
            for (String pattern : filters) {
                if (!string.isEmpty()) {
                    final int indexOf = string.indexOf(pattern);
                    if (indexOf >= 0) {
                        matchedIndex = indexOf;
                        matchedLength = pattern.length();
                        break;
                    }
                }
            }
        }
        return new FilterGroupResult(setting, matchedIndex, matchedLength);
    }
}

/**
 * If you have more than 1 filter patterns, then all instances of
 * this class should be filtered using {@link ByteArrayFilterGroupList#check(byte[])},
 * which uses a prefix tree to give better performance.
 */
class ByteArrayFilterGroup extends FilterGroup<byte[]> {

    private volatile int[][] skipTables;

    private static int indexOf(final byte[] data, final byte[] pattern, final int[] skipTable) {
        // Finds the first occurrence of the pattern in the byte array using
        // Boyer-Moore-Horspool algorithm.
        int dataLength = data.length;
        int patternLength = pattern.length;
        int difference = dataLength - patternLength;
        if (patternLength == 0) return 0; // Edge case
        
        for (int index = 0; index <= difference; ) {
            int lastCharLength = patternLength - 1;
            
            while (lastCharLength >= 0 && data[index + lastCharLength] == pattern[lastCharLength]) {
                lastCharLength--;
            }
            
            if (lastCharLength < 0) return index;
            
            byte lastCharInWindow = data[index + patternLength - 1];
            int skipDistance = skipTable[lastCharInWindow & 0xFF];
            
            index += skipDistance;
        }
        
        return -1;
    }

    private static int[] buildSkipTable(byte[] pattern) {
        int[] skipTable = new int[256];
        
        for (int i = 0; i < 256; i++) {
            skipTable[i] = pattern.length;
        }
        
        int lastCharLength = pattern.length - 1;
        for (int i = 0; i < lastCharLength; i++) {
            skipTable[pattern[i] & 0xFF] = lastCharLength - i;
        }
        
        return skipTable;
    }

    public ByteArrayFilterGroup(BooleanSetting setting, byte[]... filters) {
        super(setting, filters);
    }

    /**
     * Converts the Strings into byte arrays. Used to search for text in binary data.
     */
    public ByteArrayFilterGroup(BooleanSetting setting, String... filters) {
        super(setting, ByteTrieSearch.convertStringsToBytes(filters));
    }

    private synchronized void buildSkipTables() {
        if (skipTables != null) return; // Thread race and another thread already initialized the search.
        Logger.printDebug(() -> "Building skip tables for: " + this);
        int[][] skipTables = new int[filters.length][];
        int i = 0;
        for (byte[] pattern : filters) {
            skipTables[i++] = buildSkipTable(pattern);
        }
        this.skipTables = skipTables; // Must set after initialization finishes.
    }

    @Override
    public FilterGroupResult check(final byte[] bytes) {
        int matchedLength = 0;
        int matchedIndex = -1;
        if (isEnabled()) {
            int[][] tables = skipTables;
            if (tables == null) {
                buildSkipTables(); // Lazy load.
                tables = skipTables;
            }
            for (int i = 0, length = filters.length; i < length; i++) {
                byte[] filter = filters[i];
                matchedIndex = indexOf(bytes, filter, tables[i]);
                if (matchedIndex >= 0) {
                    matchedLength = filter.length;
                    break;
                }
            }
        }
        return new FilterGroupResult(setting, matchedIndex, matchedLength);
    }
}

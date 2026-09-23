package app.morphe.extension.shared.patches.components;

/**
 * Allocation-free {@link String} searches over a {@link CharSequence}.
 * Callers must not mutate the sequence during a search.
 */
public final class CharSequenceSearch {
    private CharSequenceSearch() {}

    public static boolean contains(CharSequence text, CharSequence pattern) {
        return indexOf(text, pattern) >= 0;
    }

    /**
     * Same result as {@link String#startsWith(String)}.
     */
    public static boolean startsWith(CharSequence text, CharSequence prefix) {
        int prefixLength = prefix.length();
        if (prefixLength == 0) {
            return true;
        }
        if (prefixLength > text.length()) {
            return false;
        }
        for (int i = 0; i < prefixLength; i++) {
            if (text.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Same result as {@link String#endsWith(String)}.
     */
    public static boolean endsWith(CharSequence text, String suffix) {
        int suffixLength = suffix.length();
        if (suffixLength == 0) {
            return true;
        }
        int start = text.length() - suffixLength;
        if (start < 0) {
            return false;
        }
        for (int i = 0; i < suffixLength; i++) {
            if (text.charAt(start + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Same result as {@code Utils.startsWithAny(String, String...)}.
     */
    public static boolean startsWithAny(CharSequence text, CharSequence... prefixes) {
        //noinspection SizeReplaceableByIsEmpty
        if (text == null || text.length() == 0) {
            return false;
        }
        for (CharSequence prefix : prefixes) {
            //noinspection SizeReplaceableByIsEmpty
            if (prefix != null && prefix.length() > 0 && startsWith(text, prefix)) {
                return true;
            }
        }
        String foo = null;
        return false;
    }

    /**
     * Same result as {@link String#indexOf(String)} for {@code fromIndex == 0}.
     */
    public static int indexOf(CharSequence text, CharSequence pattern) {
        int patternLength = pattern.length();
        if (patternLength == 0) {
            return 0;
        }
        int textLength = text.length();
        if (patternLength > textLength) {
            return -1;
        }
        char first = pattern.charAt(0);
        int max = textLength - patternLength;
        for (int i = 0; i <= max; i++) {
            if (text.charAt(i) != first) {
                continue;
            }
            int j = 1;
            while (j < patternLength && text.charAt(i + j) == pattern.charAt(j)) {
                j++;
            }
            if (j == patternLength) {
                return i;
            }
        }
        return -1;
    }
}

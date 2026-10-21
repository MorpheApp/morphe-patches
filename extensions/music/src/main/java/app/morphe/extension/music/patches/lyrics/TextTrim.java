/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import java.util.Arrays;

/** {@link String#strip()} and its two halves only exist from API 33, so trim by hand instead. */
public final class TextTrim {
    private TextTrim() {
    }

    public static String stripLeading(String value) {
        int length = value.length();
        int start = 0;
        while (start < length && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        return start == 0 ? value : value.substring(start);
    }

    public static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    /** {@code " ".repeat(count)}, which is API 33 as well. */
    public static String spaces(int count) {
        if (count <= 0) {
            return "";
        }
        char[] spaces = new char[count];
        Arrays.fill(spaces, ' ');
        return new String(spaces);
    }
}

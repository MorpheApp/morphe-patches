/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * One reading per Han character, read from a file of {@code "<character> <reading>"} lines in code
 * point order, which is what makes a lookup a binary search.
 */
final class CharReadingTable {

    private final char[] keys;
    private final String[] values;

    private CharReadingTable(char[] keys, String[] values) {
        this.keys = keys;
        this.values = values;
    }

    static CharReadingTable load(File file) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        char[] keys = new char[lines.size()];
        String[] values = new String[lines.size()];

        int count = 0;
        for (String line : lines) {
            if (line.length() < 3) continue;
            keys[count] = line.charAt(0);
            values[count] = line.substring(2);
            count++;
        }
        // Unused slots at the end would break the ordering the search relies on.
        return new CharReadingTable(Arrays.copyOf(keys, count), Arrays.copyOf(values, count));
    }

    int size() {
        return keys.length;
    }

    /** The reading of a character, or null when the table has none for it. */
    @Nullable
    String reading(char c) {
        int low = 0;
        int high = keys.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            // Unsigned: half of the Han block is negative as a char, and the table is in code point order.
            int here = Character.compare(keys[middle], c);
            if (here < 0) {
                low = middle + 1;
            } else if (here > 0) {
                high = middle - 1;
            } else {
                return values[middle];
            }
        }
        return null;
    }
}

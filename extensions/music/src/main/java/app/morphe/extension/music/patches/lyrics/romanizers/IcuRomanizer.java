/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import android.icu.text.Transliterator;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/** System ICU transliterator; reads script-by-script, needs no download. */
public final class IcuRomanizer {

    /** Any script to Latin, then ASCII: the standard recipe for a reading with no diacritics. */
    private static final String RULE =
            "Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Latin-ASCII";

    /** A single instance is safe to reuse and far cheaper than building one per line. */
    @Nullable
    private static Transliterator reader;

    private IcuRomanizer() {
    }

    /** One reading per line, or null when nothing was read so the next provider takes the turn. */
    @Nullable
    public static List<String> romanize(List<String> lines) {
        final Transliterator current = require();
        if (current == null) {
            return null;
        }
        List<String> result = new ArrayList<>(lines.size());
        boolean applied = false;
        for (String line : lines) {
            final String reading = current.transliterate(line);
            applied |= !reading.equals(line);
            result.add(reading);
        }
        return applied ? result : null;
    }

    /** The transliterator, or null below Android 10, which has no {@link Transliterator} class. */
    @Nullable
    private static synchronized Transliterator require() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        if (reader == null) {
            try {
                reader = Transliterator.getInstance(RULE);
            } catch (RuntimeException ex) {
                Logger.printInfo(() -> "ICU transliterator is not available", ex);
                return null;
            }
        }
        return reader;
    }
}

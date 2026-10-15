/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import android.icu.text.Transliterator;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.shared.Logger;

public final class CharactersConverter {
    @Nullable
    private static final Transliterator TO_TRADITIONAL =
            create("Simplified-Traditional", "Hans-Hant", "Hani-Hant");
    @Nullable
    private static final Transliterator TO_SIMPLIFIED =
            create("Traditional-Simplified", "Hant-Hans", "Hani-Hans");
    @Nullable
    private static final Transliterator TO_HALFWIDTH =
            create("Fullwidth-Halfwidth");
    @Nullable
    private static final Transliterator TO_NFKC =
            create("Any-NFKC");
    @Nullable
    private static final Transliterator TO_ASCII =
            create("Latin-ASCII");
    @Nullable
    private static final Transliterator TO_KATAKANA =
            create("Hiragana-Katakana");
    @Nullable
    private static final Transliterator TO_HIRAGANA =
            create("Katakana-Hiragana");
    @Nullable
    private static final Transliterator TO_LOWER =
            create("Any-Lower");

    private CharactersConverter() {
    }

    @Nullable
    private static Transliterator create(String... ids) {
        for (String id : ids) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return Transliterator.getInstance(id);
                }
            } catch (RuntimeException ex) {
                Logger.printDebug(() -> "Could not create Transliterator for ID: " + id, ex);
                // Try the next candidate id.
            }
        }
        return null;
    }

    @NonNull
    public static String toTraditional(@NonNull String text) {
        return transliterate(TO_TRADITIONAL, text);
    }

    @NonNull
    public static String toSimplified(@NonNull String text) {
        return transliterate(TO_SIMPLIFIED, text);
    }

    @NonNull
    private static String transliterate(@Nullable Transliterator transliterator, @NonNull String text) {
        if (transliterator == null) {
            return text;
        }
        // Transliterator is not thread safe, and the argument is always one of the shared
        // instances above, so locking on it serializes the calls against that one instance.
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (transliterator) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return transliterator.transliterate(text);
            }
        }
        return text;
    }

    /**
     * Folds text to one form so a user's regex matches whatever variant the source used.
     * Order matters: fullwidth and case before NFKC, then diacritics, then whitespace.
     */
    @NonNull
    public static String normalize(@NonNull String text) {
        String result = text;
        result = transliterate(TO_HALFWIDTH, result);
        result = transliterate(TO_LOWER, result);
        result = transliterate(TO_NFKC, result);
        result = transliterate(TO_ASCII, result);
        result = result.trim().replaceAll("\\s+", " ");
        return result;
    }

    @NonNull
    public static String normalizePreserveCase(@NonNull String text) {
        String result = text;
        result = transliterate(TO_HALFWIDTH, result);
        result = transliterate(TO_NFKC, result);
        result = transliterate(TO_ASCII, result);
        result = result.trim().replaceAll("\\s+", " ");
        return result;
    }

    /**
     * Single-transform variants of the track metadata, to broaden search coverage: Simplified and
     * Traditional Chinese, fullwidth, NFKC, Latin to ASCII, Hiragana to Katakana. Applied
     * independently rather than chained, to keep the count manageable; duplicates dropped.
     */
    @NonNull
    public static List<TrackInfo> variants(@NonNull TrackInfo track) {
        Set<TrackInfo> variants = new LinkedHashSet<>();

        addVariant(variants, track, TO_TRADITIONAL);
        addVariant(variants, track, TO_SIMPLIFIED);
        addVariant(variants, track, TO_HALFWIDTH);
        addVariant(variants, track, TO_NFKC);
        addVariant(variants, track, TO_ASCII);
        addVariant(variants, track, TO_KATAKANA);
        addVariant(variants, track, TO_HIRAGANA);

        return new ArrayList<>(variants);
    }

    @NonNull
    public static List<String> variants(@NonNull String text) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(text);
        variants.add(toTraditional(text));
        variants.add(toSimplified(text));
        String normalized = normalize(text);
        variants.add(normalized);
        variants.add(toTraditional(normalized));
        variants.add(toSimplified(normalized));
        return new ArrayList<>(variants);
    }

    private static void addVariant(@NonNull Set<TrackInfo> variants, @NonNull TrackInfo track,
                                   @Nullable Transliterator transliterator) {
        if (transliterator == null) {
            return;
        }
        TrackInfo converted = new TrackInfo(
                transliterate(transliterator, track.title()),
                transliterate(transliterator, track.artist()),
                transliterate(transliterator, track.album()),
                track.durationSeconds());
        if (!converted.equals(track)) {
            variants.add(converted);
        }
    }
}

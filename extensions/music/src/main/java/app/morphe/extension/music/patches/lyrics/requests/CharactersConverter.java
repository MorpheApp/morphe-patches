/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.icu.text.Transliterator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.TrackInfo;

public final class CharactersConverter {
    @Nullable
    private static final Transliterator TO_TRADITIONAL =
            create("Simplified-Traditional", "Hans-Hant", "Hani-Hant");
    @Nullable
    private static final Transliterator TO_SIMPLIFIED =
            create("Traditional-Simplified", "Hant-Hans", "Hani-Hans");

    private CharactersConverter() {
    }

    @Nullable
    private static Transliterator create(String... ids) {
        for (String id : ids) {
            try {
                return Transliterator.getInstance(id);
            } catch (RuntimeException ignored) {
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
        synchronized (transliterator) {
            return transliterator.transliterate(text);
        }
    }

    /**
     * Variants of the track with the title, artist and album converted between Simplified and
     * Traditional Chinese. Variants identical to the original (no conversion occurred) or to
     * each other are omitted so only useful extra queries are made.
     */
    @NonNull
    public static List<TrackInfo> variants(@NonNull TrackInfo track) {
        final List<TrackInfo> variants = new ArrayList<>(2);

        final TrackInfo traditional = new TrackInfo(
                toTraditional(track.title()),
                toTraditional(track.artist()),
                toTraditional(track.album()),
                track.durationSeconds());
        final TrackInfo simplified = new TrackInfo(
                toSimplified(track.title()),
                toSimplified(track.artist()),
                toSimplified(track.album()),
                track.durationSeconds());

        if (!traditional.equals(track) && !traditional.equals(simplified)) {
            variants.add(traditional);
        }
        if (!simplified.equals(track)) {
            variants.add(simplified);
        }
        return variants;
    }
}

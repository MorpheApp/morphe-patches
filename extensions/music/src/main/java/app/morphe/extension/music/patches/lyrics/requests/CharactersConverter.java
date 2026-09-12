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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.TrackInfo;

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
     * Normalises text for string matching by converting various character variants to their
     * standard forms. The chain is intentionally ordered:
     * <ol>
     *   <li>Fullwidth → Halfwidth (letters, numbers, punctuation)</li>
     *   <li>Any-Lower (case folding)</li>
     *   <li>NFKC Unicode normalisation (compatibility decomposition)</li>
     *   <li>Latin → ASCII (removes diacritics, e.g. Beyoncé → Beyonce)</li>
     *   <li>Whitespace collapsing (trim + collapse runs to single space)</li>
     * </ol>
     * Unlike the per-variant transforms in {@link #variants(TrackInfo)}, this method chains
     * all normalisation steps into a single pass. It is designed for use in metadata regex
     * cleaning and lyrics text filtering so that user-configured patterns always match
     * regardless of the character variant used in the source text.
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
     * Generates character variants of the track metadata to broaden lyrics search coverage.
     * Each variant applies a single character transformation to the title, artist and album fields:
     * <ul>
     *   <li>Simplified ↔ Traditional Chinese</li>
     *   <li>Fullwidth → Halfwidth (Japanese, Korean, Chinese punctuation)</li>
     *   <li>NFKC Unicode normalization</li>
     *   <li>Latin → ASCII (removes diacritics, e.g. Beyoncé → Beyonce)</li>
     *   <li>Hiragana ↔ Katakana (Japanese)</li>
     * </ul>
     * Variants are generated independently from the original (not chained) to keep the number
     * manageable. Variants identical to the original or to each other are omitted.
     * <p>
     * The order follows expected usefulness: CJK script variants first, then normalisation,
     * then Latin and Japanese script variants.
     */
    @NonNull
    public static List<TrackInfo> variants(@NonNull TrackInfo track) {
        final Set<TrackInfo> variants = new LinkedHashSet<>();

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
        final Set<String> variants = new LinkedHashSet<>();
        variants.add(text);
        variants.add(toTraditional(text));
        variants.add(toSimplified(text));
        final String normalized = normalize(text);
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
        final TrackInfo converted = new TrackInfo(
                transliterate(transliterator, track.title()),
                transliterate(transliterator, track.artist()),
                transliterate(transliterator, track.album()),
                track.durationSeconds());
        if (!converted.equals(track)) {
            variants.add(converted);
        }
    }
}

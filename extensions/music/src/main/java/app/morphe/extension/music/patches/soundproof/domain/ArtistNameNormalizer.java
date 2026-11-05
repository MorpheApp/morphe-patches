/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns an artist name, as shown by YouTube Music, into a SoundProof id.
 * <p>
 * Every id of the database is exactly the name lowercased with each space replaced by a dash,
 * so the same rule is applied here after cleaning what YouTube Music adds around the name.
 */
public final class ArtistNameNormalizer {

    /** Auto-generated YouTube channels are named "Artist - Topic". */
    private static final Pattern TOPIC_CHANNEL_SUFFIX = Pattern.compile("(?i)\\s*-\\s*topic$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private ArtistNameNormalizer() {
    }

    /**
     * @return The SoundProof id of the artist, or an empty string when the name is blank.
     */
    public static String toArtistId(String artistName) {
        return toDisplayName(artistName).toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    /**
     * @return The name without the topic channel suffix and with single spaces, case kept.
     */
    public static String toDisplayName(String artistName) {
        String withoutTopicSuffix = TOPIC_CHANNEL_SUFFIX.matcher(artistName.trim()).replaceAll("");
        return WHITESPACE_RUN.matcher(withoutTopicSuffix.trim()).replaceAll(" ");
    }
}

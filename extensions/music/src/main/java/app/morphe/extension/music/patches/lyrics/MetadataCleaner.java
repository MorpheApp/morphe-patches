/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Normalizes YouTube Music metadata into what a lyrics database expects.
 *
 * <p>Separate from the scrobbling cleaner, which is driven by scrobbling settings.
 */
final class MetadataCleaner {

    private static final String VIDEO_SUFFIX_PATTERN =
            "(?i)\\s*[（(\\[](official\\s+)?(video|audio|music\\s+video|lyrics?\\s+video|visualizer|mv)[）)\\]]";
    private static final String REMASTER_PATTERN =
            "(?i)\\s*[（(\\[](\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?[）)\\]]";
    private static final String QUALITY_PATTERN =
            "(?i)\\s*[（(\\[](mono|stereo|hq|hd|4k|8k)[）)\\]]";
    private static final String TOPIC_SUFFIX_PATTERN = "(?i)\\s*-\\s*topic$";

    private MetadataCleaner() {
    }

    @NonNull
    static String cleanTitle(@Nullable String title) {
        if (title == null) {
            return "";
        }
        String clean = title
                .replaceAll(VIDEO_SUFFIX_PATTERN, "")
                .replaceAll(REMASTER_PATTERN, "")
                .replaceAll(QUALITY_PATTERN, "");
        return collapseWhitespace(clean);
    }

    @NonNull
    static String cleanArtist(@Nullable String artist) {
        if (artist == null) {
            return "";
        }
        String clean = artist.replaceAll(TOPIC_SUFFIX_PATTERN, "");

        // Multi artist strings such as "A, B & C" rarely match a database entry,
        // so only the first credited artist is used for the lookup.
        int separator = indexOfFirstSeparator(clean);
        if (separator > 0) {
            clean = clean.substring(0, separator);
        }
        return collapseWhitespace(clean);
    }

    @NonNull
    static String cleanAlbum(@Nullable String album) {
        if (album == null) {
            return "";
        }
        return collapseWhitespace(album.replaceAll(REMASTER_PATTERN, ""));
    }

    private static int indexOfFirstSeparator(@NonNull String artist) {
        final String[] separators = {" & ", ", ", " x ", " X ", " feat. ", " ft. ", " с "};
        int result = -1;
        for (String separator : separators) {
            int index = artist.indexOf(separator);
            if (index > 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    @NonNull
    private static String collapseWhitespace(@NonNull String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}

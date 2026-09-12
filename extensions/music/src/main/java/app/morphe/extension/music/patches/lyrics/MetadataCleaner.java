/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;

/**
 * Normalizes YouTube Music metadata into what a lyrics database expects.
 *
 * <p>All cleanup is driven by the user-configured {@link app.morphe.extension.music.settings.Settings#LYRICS_CUSTOM_REGEX}.
 * When the regex is blank no filtering is applied.
 */
final class MetadataCleaner {

    private MetadataCleaner() {
    }

    static String cleanTitle(@Nullable String title) {
        if (title == null) {
            return "";
        }
        return collapseWhitespace(applyRegex(title, app.morphe.extension.music.settings.Settings.LYRICS_CUSTOM_REGEX.get()));
    }

    static String cleanArtist(@Nullable String artist) {
        if (artist == null) {
            return "";
        }
        String clean = artist;

        // Multi artist strings such as "A, B & C" rarely match a database entry,
        // so only the first credited artist is used for the lookup.
        int separator = indexOfFirstSeparator(clean);
        if (separator > 0) {
            clean = clean.substring(0, separator);
        }
        return collapseWhitespace(applyRegex(clean, app.morphe.extension.music.settings.Settings.LYRICS_CUSTOM_REGEX.get()));
    }

    static String cleanAlbum(@Nullable String album) {
        if (album == null) {
            return "";
        }
        return collapseWhitespace(applyRegex(album, app.morphe.extension.music.settings.Settings.LYRICS_CUSTOM_REGEX.get()));
    }

    static String applyRegex(String input, String regex) {
        if (regex == null || regex.isBlank()) {
            return input;
        }
        try {
            return CharactersConverter.normalizePreserveCase(input).replaceAll(regex, "");
        } catch (Exception ex) {
            return input;
        }
    }

    static String[] parseTitleAndArtist(@Nullable String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        int idx = rawTitle.indexOf(" - ");
        if (idx <= 0 || idx >= rawTitle.length() - 3) {
            return null;
        }
        String artist = cleanArtist(rawTitle.substring(0, idx).trim());
        String title = cleanTitle(rawTitle.substring(idx + 3).trim());
        if (artist.isEmpty() || title.isEmpty()) {
            return null;
        }
        return new String[]{ artist, title };
    }

    @Nullable
    static TrackInfo swapTitleAndArtist(TrackInfo track, @Nullable String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        int idx = rawTitle.indexOf(" - ");
        if (idx <= 0 || idx >= rawTitle.length() - 3) {
            return null;
        }
        String left = rawTitle.substring(0, idx).trim();
        String right = rawTitle.substring(idx + 3).trim();
        // Original split: left=artist, right=title → swapped: left=title, right=artist
        String swappedArtist = cleanArtist(right);
        String swappedTitle = cleanTitle(left);
        if (swappedArtist.isEmpty() || swappedTitle.isEmpty()) {
            return null;
        }
        TrackInfo swapped = new TrackInfo(swappedTitle, swappedArtist, track.album(),
                track.durationSeconds());
        return swapped.equals(track) ? null : swapped;
    }

    private static int indexOfFirstSeparator(String artist) {
        final String[] separators = {" & ", ", ", " x ", " X ", " feat. ", " ft. ", " с ", " 和 "};
        int result = -1;
        for (String separator : separators) {
            int index = artist.indexOf(separator);
            if (index > 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    static String[] splitArtists(String artist) {
        return artist.split("\\s*(?:和|&|feat\\.?|ft\\.?|,|/)\\s*");
    }

    private static String collapseWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}

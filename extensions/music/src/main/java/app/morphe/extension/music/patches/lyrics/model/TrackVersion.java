/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * The title of a track without its version, and the version itself, such as "Acoustic".
 * Both come from separate backend fields. The version is never parsed out of a title:
 * "Rockstar (Olympia)" is a title, not a version.
 *
 * @param variant The version, or an empty string if the track has none.
 */
public record TrackVersion(String title, String variant) {

    /** Version matching needs untouched metadata, rather than the cleaned lyrics query. */
    public static TrackInfo query(TrackInfo track, @Nullable String rawTitle, @Nullable String rawArtist) {
        return new TrackInfo(rawTitle == null || rawTitle.isBlank() ? track.title() : rawTitle,
                rawArtist == null || rawArtist.isBlank() ? track.artist() : rawArtist,
                track.album(), track.durationSeconds());
    }

    /**
     * Strips punctuation from both ends of the version, but only punctuation with no partner left
     * in the rest of the version. "(Acoustic)" becomes "Acoustic", "Acoustic (Deluxe)" keeps its
     * brackets, and "Acoustic (Deluxe)," loses only the comma.
     */
    public static String clean(String version) {
        String s = version.strip();
        while (!s.isEmpty()) {
            String core = core(s);
            final int first = s.codePointAt(0);
            if (isEdgePunctuation(first) && !hasPartner(first, core)) {
                s = s.substring(Character.charCount(first)).strip();
                continue;
            }
            final int last = s.codePointBefore(s.length());
            if (isEdgePunctuation(last) && !hasPartner(last, core)) {
                s = s.substring(0, s.length() - Character.charCount(last)).strip();
                continue;
            }
            break;
        }
        return s;
    }

    /** The version without the punctuation and whitespace at either end. */
    private static String core(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isEdge(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isEdge(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    private static boolean isEdge(int cp) {
        return isEdgePunctuation(cp) || Character.isWhitespace(cp);
    }

    private static boolean isEdgePunctuation(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean hasPartner(int cp, String core) {
        String symbol = new String(Character.toChars(cp));
        for (PairedPunctuation.Pair pair : PairedPunctuation.PAIRS) {
            if (pair.opening().equals(symbol) && core.contains(pair.closing())
                    || pair.closing().equals(symbol) && core.contains(pair.opening())) {
                return true;
            }
        }
        return false;
    }

    /**
     * If the displayed title is the backend's short title and version put together. The
     * displayed title itself is never parsed.
     */
    public static boolean matchesStructuredTitle(String displayed, String full, String shortTitle, String version) {
        if (shortTitle.isBlank()) return false;
        String wanted = normalize(displayed);
        String variant = clean(version);
        if (variant.isEmpty()) {
            return wanted.equals(normalize(shortTitle)) && normalize(full).equals(normalize(shortTitle));
        }
        return wanted.equals(normalize(shortTitle + " (" + variant + ")"));
    }

    public static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT);
    }
}

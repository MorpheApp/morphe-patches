/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Parser for the LRC format used by both LRCLIB and KuGou.
 */
public final class LrcParser {

    /** Tags such as {@code [ar:Artist]} that are not timestamps. */
    private static final String METADATA_TAG_CHARACTERS = "abcdefghijklmnopqrstuvwxyz";

    private LrcParser() {
    }

    /**
     * Parses synced LRC content.
     *
     * @return Lines sorted by time, or an empty list if nothing could be parsed.
     */
    public static List<LyricsLine> parseSynced(@Nullable String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            return Collections.emptyList();
        }

        List<LyricsLine> lines = new ArrayList<>();
        // The LRC "offset" tag shifts every timestamp, and is applied while parsing
        // because it belongs to the file rather than to the user configured offset.
        long fileOffsetMs = 0;

        for (String rawLine : lrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            List<Long> timestamps = new ArrayList<>(1);
            int index = 0;

            while (index < line.length() && line.charAt(index) == '[') {
                int end = line.indexOf(']', index);
                if (end < 0) {
                    break;
                }

                String tag = line.substring(index + 1, end);
                if (isMetadataTag(tag)) {
                    Long offset = parseOffsetTag(tag);
                    if (offset != null) {
                        fileOffsetMs = offset;
                    }
                } else {
                    long time = parseTimestamp(tag);
                    if (time != LyricsLine.NO_TIME) {
                        timestamps.add(time);
                    }
                }

                index = end + 1;
            }

            if (timestamps.isEmpty()) {
                continue;
            }

            String text = stripWordTimestamps(line.substring(index)).trim();
            for (long time : timestamps) {
                lines.add(new LyricsLine(Math.max(0, time + fileOffsetMs), text));
            }
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    /**
     * Parses plain (unsynced) lyrics, one line per text line.
     */
    public static List<LyricsLine> parsePlain(@Nullable String plain) {
        if (plain == null || plain.isEmpty()) {
            return Collections.emptyList();
        }

        List<LyricsLine> lines = new ArrayList<>();
        for (String rawLine : plain.split("\\r?\\n")) {
            lines.add(new LyricsLine(LyricsLine.NO_TIME, rawLine.trim()));
        }

        // Trailing blank lines add nothing but scroll space.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).text().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * @return {@code true} if the tag is metadata such as {@code ti} or {@code offset}.
     */
    private static boolean isMetadataTag(String tag) {
        int colon = tag.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String name = tag.substring(0, colon).toLowerCase(Locale.ROOT);
        for (int i = 0; i < name.length(); i++) {
            if (METADATA_TAG_CHARACTERS.indexOf(name.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param tag Tag that {@link #isMetadataTag} already accepted, so it holds a colon.
     */
    @Nullable
    private static Long parseOffsetTag(String tag) {
        final int colon = tag.indexOf(':');
        if (!tag.substring(0, colon).equalsIgnoreCase("offset")) {
            return null;
        }
        try {
            String value = tag.substring(colon + 1).trim();
            if (value.startsWith("+")) {
                value = value.substring(1);
            }
            // A positive LRC offset means the lyrics are shown earlier.
            return -Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses {@code mm:ss.xx}, {@code mm:ss.xxx} or {@code mm:ss}.
     *
     * @return Time in milliseconds, or {@link LyricsLine#NO_TIME} if the tag is not a timestamp.
     */
    private static long parseTimestamp( String tag) {
        try {
            int colon = tag.indexOf(':');
            if (colon <= 0) {
                return LyricsLine.NO_TIME;
            }

            long minutes = Long.parseLong(tag.substring(0, colon).trim());

            String rest = tag.substring(colon + 1).trim();
            int dot = rest.indexOf('.');
            if (dot < 0) {
                dot = rest.indexOf(':');
            }

            long seconds;
            long fractionMs = 0;
            if (dot < 0) {
                seconds = Long.parseLong(rest);
            } else {
                seconds = Long.parseLong(rest.substring(0, dot));
                String fraction = rest.substring(dot + 1);
                if (fraction.length() == 1) {
                    fractionMs = Long.parseLong(fraction) * 100;
                } else if (fraction.length() == 2) {
                    fractionMs = Long.parseLong(fraction) * 10;
                } else {
                    fractionMs = Long.parseLong(fraction.substring(0, 3));
                }
            }

            return (minutes * 60 + seconds) * 1000 + fractionMs;
        } catch (NumberFormatException | IndexOutOfBoundsException ex) {
            Logger.printDebug(() -> "Not a timestamp: " + tag);
            return LyricsLine.NO_TIME;
        }
    }

    /**
     * Removes word level timestamps of enhanced LRC, such as {@code <00:12.00>}.
     */
    private static String stripWordTimestamps(String text) {
        if (text.indexOf('<') < 0) {
            return text;
        }

        StringBuilder builder = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '<') {
                int end = text.indexOf('>', index);
                if (end > 0) {
                    index = end + 1;
                    continue;
                }
            }
            builder.append(character);
            index++;
        }
        return builder.toString();
    }
}

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the LRC format used by both LRCLIB and KuGou.
 */
public final class LrcParser {

    /** Tags such as {@code [ar:Artist]} that are not timestamps. */
    private static final String METADATA_TAG_CHARACTERS = "abcdefghijklmnopqrstuvwxyz";

    private static final Set<String> CREDIT_META_KEYS = Set.of("ti", "ar", "al", "au");

    private static final Pattern LRC_META = Pattern.compile("^\\[(\\w+):([^\\]]*)]$");

    public static final class LrcParseResult {
        public final List<LyricsLine> lines;
        public final List<String> creditLines;

        LrcParseResult(List<LyricsLine> lines, List<String> creditLines) {
            this.lines = lines;
            this.creditLines = creditLines;
        }
    }

    private LrcParser() {
    }

    public static LrcParseResult parseSyncedWithCreditLines(@Nullable String lrc) {
        List<String> creditLines = extractCreditMetadata(lrc);
        List<LyricsLine> lines = parseSynced(lrc);
        return new LrcParseResult(lines, creditLines);
    }

    public static List<String> extractCreditMetadata(@Nullable String lrc) {
        List<String> creditLines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) {
            return creditLines;
        }
        for (String rawLine : lrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher meta = LRC_META.matcher(line);
            if (meta.matches()) {
                String key = meta.group(1).toLowerCase(Locale.ROOT);
                if (!key.equals("offset") && CREDIT_META_KEYS.contains(key)) {
                    String value = meta.group(2).trim();
                    if (!value.isEmpty()) {
                        creditLines.add(meta.group(1) + ":" + value);
                    }
                }
            }
        }
        return creditLines;
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

            final long lineStartMs = Math.max(0, timestamps.get(0) + fileOffsetMs);
            final BodyParse body = parseBody(line.substring(index), lineStartMs);
            final String text = body.text.trim();
            if (!body.words.isEmpty()) {
                for (long time : timestamps) {
                    lines.add(new LyricsLine(Math.max(0, time + fileOffsetMs), text, body.words));
                }
            } else if (!text.isEmpty()) {
                for (long time : timestamps) {
                    lines.add(new LyricsLine(Math.max(0, time + fileOffsetMs), text));
                }
            }
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    private static final class BodyParse {
        final String text;
        final List<Word> words;

        BodyParse(String text, List<Word> words) {
            this.text = text;
            this.words = words;
        }
    }

    private static BodyParse parseBody(String body, long lineStartMs) {
        if (body.indexOf('<') < 0 && body.indexOf('[') < 0) {
            // No word-level tags, so this is a plain line.
            return new BodyParse(body, List.of());
        }

        final List<Word> words = new ArrayList<>();
        final StringBuilder full = new StringBuilder();

        long pendingStart = LyricsLine.NO_TIME;
        final StringBuilder pending = new StringBuilder();
        String prefix = "";
        boolean hasToken = false;

        int index = 0;
        final int length = body.length();
        while (index < length) {
            final char c = body.charAt(index);
            if (c == '<' || c == '[') {
                final char close = (c == '<') ? '>' : ']';
                final int end = body.indexOf(close, index);
                if (end < 0) {
                    // Unterminated tag: keep the remainder as literal text.
                    full.append(body, index, length);
                    pending.append(body, index, length);
                    break;
                }
                final long time = parseTimestamp(body.substring(index + 1, end));
                if (time != LyricsLine.NO_TIME) {
                    if (pendingStart == LyricsLine.NO_TIME) {
                        // First word tag: text accumulated so far precedes any timed word.
                        prefix = pending.toString();
                        pendingStart = time;
                    } else {
                        final String word = pending.toString();
                        if (!word.trim().isEmpty()) {
                            words.add(new Word(pendingStart, LyricsLine.NO_TIME, word));
                        }
                        pendingStart = time;
                    }
                    hasToken = true;
                    pending.setLength(0);
                } else {
                    // Not a timestamp (e.g. [chorus]): keep as literal text.
                    full.append(body, index, end + 1);
                    pending.append(body, index, end + 1);
                }
                index = end + 1;
            } else {
                full.append(c);
                pending.append(c);
                index++;
            }
        }

        if (!hasToken) {
            // No valid word tag was found; treat the whole body as a plain line.
            return new BodyParse(body, List.of());
        }

        if (pendingStart != LyricsLine.NO_TIME) {
            final String word = pending.toString();
            if (!word.trim().isEmpty()) {
                words.add(new Word(pendingStart, LyricsLine.NO_TIME, word));
            }
        }

        if (!prefix.trim().isEmpty()) {
            words.add(0, new Word(lineStartMs, LyricsLine.NO_TIME, prefix.trim()));
        }

        if (words.isEmpty()) {
            return new BodyParse(full.toString().trim(), List.of());
        }

        inferWordEnds(words);
        return new BodyParse(full.toString().trim(), words);
    }

    private static void inferWordEnds(List<Word> words) {
        for (int i = 0; i < words.size() - 1; i++) {
            Word word = words.get(i);
            if (word.endMs() == LyricsLine.NO_TIME) {
                words.set(i, new Word(word.startMs(), words.get(i + 1).startMs(), word.text()));
            }
        }
        if (!words.isEmpty()) {
            int last = words.size() - 1;
            Word lastWord = words.get(last);
            if (lastWord.endMs() == LyricsLine.NO_TIME) {
                words.set(last, new Word(lastWord.startMs(),
                        lastWord.startMs() + 800, lastWord.text()));
            }
        }
    }

    public static String formatLine(LyricsLine line) {
        StringBuilder builder = new StringBuilder(formatTimestamp(line.startTimeMs()));
        if (line.hasWords()) {
            for (Word word : line.words()) {
                builder.append('<')
                        .append(formatWordTimestamp(word.startMs()))
                        .append('>')
                        .append(word.text());
                if (word.endsWithSpace()) {
                    builder.append(' ');
                }
            }
        } else {
            builder.append(line.text());
        }
        return builder.toString();
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
            return LyricsLine.NO_TIME;
        }
    }

    /**
     * Formats a line level timestamp as {@code [mm:ss.xx]} for the LRC cache.
     */
    private static String formatTimestamp(long timeMs) {
        final long minutes = timeMs / 60_000;
        final long seconds = (timeMs / 1000) % 60;
        final long hundredths = (timeMs % 1000) / 10;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths);
    }

    private static String formatWordTimestamp(long timeMs) {
        final long minutes = timeMs / 60_000;
        final long seconds = (timeMs / 1000) % 60;
        final long hundredths = (timeMs % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths);
    }
}

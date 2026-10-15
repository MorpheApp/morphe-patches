/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.shared.Logger;

/**
 * Parser for the LRC format used by both LRCLIB and KuGou.
 */
public final class LRCParser {

    /** Runs of whitespace inside a heading, collapsed so {@code [Pre - Chorus]} reads as one phrase. */
    private static final Pattern HEADING_SPACES = Pattern.compile("\\s+");

    /** Tags such as {@code [ar:Artist]} that are not timestamps. */
    private static final String METADATA_TAG_CHARACTERS = "abcdefghijklmnopqrstuvwxyz";

    private static final String BG_TAG = "bg:1";

    public static final Set<String> CREDIT_META_KEYS = Set.of("ti", "ar", "al", "au");

    public static final Pattern LRC_META = Pattern.compile("^\\[(\\w+):([^]]*)]$");

    public static final class LrcParseResult {
        public final List<LyricsLine> lines;
        public final List<String> creditLines;

        LrcParseResult(List<LyricsLine> lines, List<String> creditLines) {
            this.lines = lines;
            this.creditLines = creditLines;
        }
    }

    private LRCParser() {
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
                String tag = meta.group(1);
                String rawValue = meta.group(2);
                if (tag == null || rawValue == null) {
                    continue;
                }
                String key = tag.toLowerCase(Locale.ROOT);
                if (!key.equals("offset") && CREDIT_META_KEYS.contains(key)) {
                    String value = rawValue.trim();
                    if (!value.isEmpty()) {
                        creditLines.add(tag + ":" + value);
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
            // A leading bracket that is neither metadata nor a timestamp names the section the line
            // opens, e.g. [00:10.00][Verse 1] walking down the street. The first one wins.
            String songPart = null;
            // Enhanced LRC may put the vocal agent in its own leading bracket, [v1:], rather than in
            // front of the text. A marker in the text still wins, so the two forms agree.
            String bracketedAgent = null;
            boolean isBg = false;

            while (index < line.length() && line.charAt(index) == '[') {
                int end = line.indexOf(']', index);
                if (end < 0) {
                    break;
                }

                String tag = line.substring(index + 1, end);
                if (BG_TAG.equalsIgnoreCase(tag)) {
                    isBg = true;
                } else if (isMetadataTag(tag)) {
                    Long offset = parseOffsetTag(tag);
                    if (offset != null) {
                        fileOffsetMs = offset;
                    }
                } else {
                    long time = parseTimestamp(tag);
                    if (time != LyricsLine.NO_TIME) {
                        timestamps.add(time);
                    } else if (agentMarker(tag) != null) {
                        if (bracketedAgent == null) {
                            bracketedAgent = tag;
                        }
                    } else if (songPart == null && !tag.trim().isEmpty()) {
                        songPart = collapseSpaces(tag);
                    }
                }

                index = end + 1;
            }

            if (timestamps.isEmpty()) {
                continue;
            }

            final long lineStartMs = Math.max(0, timestamps.get(0) + fileOffsetMs);
            BodyParse body = parseBody(line.substring(index), lineStartMs);
            String text = body.text.trim();
            List<Word> words = body.words;
            String agentMarker = agentMarker(text);
            String agentId = null;
            boolean isDuet = false;
            if (agentMarker != null) {
                text = text.substring(agentMarker.length()).trim();
                words = stripAgentMarker(words, agentMarker);
                agentId = agentMarker.substring(0, agentMarker.length() - 1);
                isDuet = isDuetAgent(agentId);
            } else if (bracketedAgent != null) {
                agentId = bracketedAgent.substring(0, bracketedAgent.length() - 1);
                isDuet = isDuetAgent(agentId);
            }
            if (!words.isEmpty()) {
                for (long time : timestamps) {
                    lines.add(new LyricsLine(Math.max(0, time + fileOffsetMs), LyricsLine.NO_TIME,
                            text, words, agentId, isDuet, isBg, songPart));
                }
            } else if (!text.isEmpty()) {
                for (long time : timestamps) {
                    lines.add(new LyricsLine(Math.max(0, time + fileOffsetMs), LyricsLine.NO_TIME,
                            text, List.of(), agentId, isDuet, isBg, songPart));
                }
            }
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    private record BodyParse(String text, List<Word> words) {
    }

    private static BodyParse parseBody(String body, long lineStartMs) {
        if (body.indexOf('<') < 0 && body.indexOf('[') < 0) {
            return new BodyParse(body, List.of());
        }

        List<Word> words = new ArrayList<>();
        StringBuilder full = new StringBuilder();

        long pendingStart = LyricsLine.NO_TIME;
        StringBuilder pending = new StringBuilder();
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
                    // Unterminated tag: the rest is text, not a tag.
                    full.append(body, index, length);
                    pending.append(body, index, length);
                    break;
                }
                final long time = parseTimestamp(body.substring(index + 1, end));
                if (time != LyricsLine.NO_TIME) {
                    if (pendingStart == LyricsLine.NO_TIME) {
                        prefix = pending.toString();
                    } else {
                        String word = pending.toString();
                        if (!word.trim().isEmpty()) {
                            words.add(new Word(pendingStart, LyricsLine.NO_TIME, word));
                        }
                    }
                    pendingStart = time;
                    hasToken = true;
                    pending.setLength(0);
                } else {
                    // Not a timestamp, so a section bracket like [chorus] stays in the text.
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
            return new BodyParse(body, List.of());
        }

        String word = pending.toString();
        if (!word.trim().isEmpty()) {
            words.add(new Word(pendingStart, LyricsLine.NO_TIME, word));
        }

        if (!prefix.trim().isEmpty()) {
            words.add(0, new Word(lineStartMs, LyricsLine.NO_TIME, prefix.trim()));
        }

        if (words.isEmpty()) {
            return new BodyParse(full.toString().trim(), List.of());
        }

        inferMissingWordEnds(words, 800);
        return new BodyParse(full.toString().trim(), words);
    }

    @Nullable
    private static String agentMarker(@Nullable String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != 'v') {
            return null;
        }
        int index = 1;
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        if (index == 1 || index >= text.length() || text.charAt(index) != ':') {
            return null;
        }
        return text.substring(0, index + 1);
    }

    private static List<Word> stripAgentMarker(List<Word> words, String agentMarker) {
        if (words.isEmpty()) {
            return words;
        }
        Word first = words.get(0);
        if (!first.text().startsWith(agentMarker)) {
            return words;
        }
        String remainder = first.text().substring(agentMarker.length()).stripLeading();
        List<Word> stripped = new ArrayList<>(words);
        if (remainder.isEmpty()) {
            stripped.remove(0);
        } else {
            stripped.set(0, new Word(first.startMs(), first.endMs(), remainder));
        }
        return stripped;
    }

    private static boolean isDuetAgent(String agentId) {
        return (agentId.charAt(agentId.length() - 1) - '0') % 2 == 0;
    }

    public static String formatLine(LyricsLine line) {
        StringBuilder builder = new StringBuilder(formatTimestamp(line.startTimeMs()));
        if (line.isBG()) {
            builder.append('[').append(BG_TAG).append(']');
        }
        // The section label goes back into its own bracket, right after the timestamp, which is
        // where a parser looks for it. Dropping it here would strip the label off every line that
        // comes back out of the cache.
        String songPart = line.songPart();
        if (songPart != null && !songPart.isEmpty()) {
            builder.append('[').append(songPart).append(']');
        }
        String agentMarker = agentMarker(line.agentId() == null ? null : line.agentId() + ":");
        if (agentMarker != null) {
            builder.append(agentMarker);
        }
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
     *
     * <p>A line that is entirely a bracketed tag (for example {@code [Chorus]}, as Genius and
     * several other providers publish) is a section heading rather than a lyric. It is dropped
     * from the returned lines and attached to {@link LyricsLine#songPart()} of the next line
     * that has content, so the view can show it as a label instead of singing it. Brackets that
     * only appear inside a line, such as {@code Hello [world]}, stay plain text.
     */
    public static List<LyricsLine> parsePlain(@Nullable String plain) {
        return parsePlainWithSlots(plain).lines();
    }

    /**
     * Plain lyrics together with the position every source line ended up in.
     *
     * @param lines Parsed lines, with headings removed.
     * @param slots For each line of the source text, the index it became in {@code lines}, or
     *              {@code -1} when it was a heading that was dropped. Callers aligning a
     *              translation against the source text need this, because a heading in the
     *              original shifts every following line down by one.
     */
    public record PlainLyrics(List<LyricsLine> lines, int[] slots) {
    }

    /**
     * Same parse as {@link #parsePlain}, also reporting where each source line landed. Both go
     * through one loop so the mapping can never drift from the parser that produced it.
     */
    public static PlainLyrics parsePlainWithSlots(@Nullable String plain) {
        if (plain == null || plain.isEmpty()) {
            return new PlainLyrics(Collections.emptyList(), new int[0]);
        }

        String[] raw = plain.split("\\r?\\n");
        List<LyricsLine> lines = new ArrayList<>(raw.length);
        int[] slots = new int[raw.length];
        Arrays.fill(slots, -1);
        String pendingPart = null;
        for (int i = 0; i < raw.length; i++) {
            String trimmed = raw[i].trim();
            if (isSectionHeader(trimmed)) {
                pendingPart = sectionLabel(trimmed);
                continue;
            }
            if (trimmed.isEmpty()) {
                // Keep the blank line as spacing, but do not let it carry the heading.
                lines.add(new LyricsLine(LyricsLine.NO_TIME, trimmed));
            } else {
                lines.add(new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME, trimmed,
                        List.of(), null, false, false, pendingPart));
                pendingPart = null;
            }
            slots[i] = lines.size() - 1;
        }

        // Trailing blank lines add nothing but scroll space. Their slots now point past the end
        // of the list, which callers treat as out of range.
        while (!lines.isEmpty() && lines.get(lines.size() - 1).text().isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return new PlainLyrics(lines, slots);
    }

    /**
     * @return {@code true} if the whole line is a bracketed heading, such as {@code [Verse 1]}.
     *         Bracketed metadata such as {@code [ar:Artist]} never reaches plain lyrics, and an
     *         empty {@code []} is too short to be a heading.
     */
    private static boolean isSectionHeader(String trimmed) {
        return trimmed.length() > 2 && trimmed.charAt(0) == '['
                && trimmed.charAt(trimmed.length() - 1) == ']';
    }

    /** The heading text without its brackets, with runs of whitespace collapsed. */
    private static String sectionLabel(String trimmed) {
        return collapseSpaces(trimmed.substring(1, trimmed.length() - 1));
    }

    private static String collapseSpaces(String text) {
        return HEADING_SPACES.matcher(text).replaceAll(" ").trim();
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
            Logger.printDebug(() -> "Failed to parse LRC offset tag", ex);
            return null;
        }
    }

    /**
     * Parses {@code mm:ss.xx}, {@code mm:ss.xxx} or {@code mm:ss}.
     *
     * @return Time in milliseconds, or {@link LyricsLine#NO_TIME} if the tag is not a timestamp.
     */
    private static long parseTimestamp(String tag) {
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
                fractionMs = LyricsRequests.fractionToMs(rest.substring(dot + 1));
            }

            return (minutes * 60 + seconds) * 1000 + fractionMs;
        } catch (NumberFormatException | IndexOutOfBoundsException ex) {
            Logger.printDebug(() -> "Failed to parse LRC timestamp: " + tag, ex);
            return LyricsLine.NO_TIME;
        }
    }

    /**
     * Formats a line level timestamp as {@code [mm:ss.xx]} for the LRC cache.
     */
    private static String formatTimestamp(long timeMs) {
        return "[" + formatCentiseconds(timeMs) + "]";
    }

    private static String formatWordTimestamp(long timeMs) {
        return formatCentiseconds(timeMs);
    }

    /** Formats {@code mm:ss.xx} (hundredths) in US locale. */
    public static String formatCentiseconds(long timeMs) {
        final long minutes = timeMs / 60_000;
        final long seconds = (timeMs / 1000) % 60;
        final long hundredths = (timeMs % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths);
    }

    /** Fills missing word end times: end = next start, last word = start + {@code lastFallbackMs}. */
    public static void inferMissingWordEnds(List<Word> words, long lastFallbackMs) {
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
                        lastWord.startMs() + lastFallbackMs, lastWord.text()));
            }
        }
    }
}

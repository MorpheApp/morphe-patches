/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text of the miniplayer and the media session. The lyrics themselves are never changed. */
public final class MiniLyricsFormatter {
    public static final String DEFAULT_TRACK_FORMAT = "{t} - {a}";
    public static final String ARTIST_FIRST_TRACK_FORMAT = "{a} - {t}";
    public static final String DEFAULT_ENCAPSULATION = "{lrc} :(x{n}):";
    public static final String DEFAULT_INSTRUMENTAL = "♪\n♪♪\n♪♪♪";
    private static final String LYRIC_PLACEHOLDER = "{lrc}";
    /** An extended grapheme cluster, so a character with combining marks or an emoji is not split. */
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    public record Chunk(String text, long startMs) {
    }

    private record Unit(int start, int end, long timeMs) {
    }

    private MiniLyricsFormatter() {
    }

    public static boolean validEncapsulation(@Nullable String template) {
        return template != null && template.contains(LYRIC_PLACEHOLDER);
    }

    public static String trackLabel(@Nullable String template, @Nullable String title, @Nullable String artist) {
        return replacePlaceholders(template == null ? DEFAULT_TRACK_FORMAT : template, title, artist, null);
    }

    /**
     * Replaces {t} with the title, {a} with the artist and {lrc} with the lyric if one is given.
     * This is done in one pass, so a placeholder in a song title is kept as is.
     */
    private static String replacePlaceholders(String template, @Nullable String title,
                                              @Nullable String artist, @Nullable String lyric) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0, length = template.length(); i < length; ) {
            if (template.startsWith("{t}", i)) {
                out.append(title == null ? "" : title);
                i += 3;
            } else if (template.startsWith("{a}", i)) {
                out.append(artist == null ? "" : artist);
                i += 3;
            } else if (lyric != null && template.startsWith(LYRIC_PLACEHOLDER, i)) {
                out.append(lyric);
                i += LYRIC_PLACEHOLDER.length();
            } else {
                out.append(template.charAt(i++));
            }
        }
        return out.toString();
    }

    /**
     * {lrc} is the lyric and {n} its repeat count. Text between unescaped colons appears only when
     * the count is above 1; a backslash before a colon keeps it literal.
     */
    public static String encapsulate(@Nullable String template, String lyric, int repeat) {
        final String source = validEncapsulation(template) ? template : DEFAULT_ENCAPSULATION;
        final StringBuilder out = new StringBuilder();
        final StringBuilder segment = new StringBuilder();
        boolean inside = false;
        for (int i = 0; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length() && source.charAt(i + 1) == ':') {
                segment.append(':');
                i++;
            } else if (c == ':') {
                if (!inside || repeat > 1) out.append(segment);
                segment.setLength(0);
                inside = !inside;
            } else {
                segment.append(c);
            }
        }
        if (!inside || repeat > 1) out.append(segment);
        return out.toString().replace("{n}", String.valueOf(repeat)).replace(LYRIC_PLACEHOLDER, lyric);
    }

    /** How many times in a row the line at the index is sung, counting up to and including it. */
    public static int repeatCount(List<LyricsLine> lines, int index) {
        final String text = lines.get(index).text();
        int count = 1;
        for (int i = index - 1; i >= 0 && lines.get(i).text().equals(text); i--) {
            count++;
        }
        return count;
    }

    public static int characterCount(String text) {
        final Matcher matcher = GRAPHEME.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /** The chunk of a line at the position, put into the encapsulation template. */
    public static String current(List<Chunk> chunks, long positionMs, @Nullable String wrapper, int repeat) {
        if (chunks.isEmpty()) return "";
        Chunk selected = chunks.get(0);
        for (int i = 1; i < chunks.size(); i++) {
            Chunk candidate = chunks.get(i);
            if (candidate.startMs() < 0 || candidate.startMs() > positionMs) break;
            selected = candidate;
        }
        return selected.text().isEmpty() ? "" : encapsulate(wrapper, selected.text(), repeat);
    }

    /**
     * Splits a line longer than the limit into two halves of about equal length, by words or by
     * characters if the line has no spaces. A half starts at the time its first word is sung, or
     * at a time in proportion to its position in the line if the words have no times.
     */
    public static List<Chunk> chunks(LyricsLine line, long intervalEndMs, int limit,
                                     boolean capitalize, boolean strip) {
        final LyricsTextFormatter.Result cleaned = LyricsTextFormatter.format(line.text(), false, strip);
        final String text = cleaned.text();
        if (limit <= 0 || characterCount(text) <= limit) {
            return List.of(new Chunk(LyricsTextFormatter.format(text, capitalize, false).text(),
                    line.startTimeMs()));
        }
        final List<Unit> timed = timedUnits(line, cleaned);
        final List<Unit> units = timed.isEmpty() ? untimedUnits(text) : timed;
        final List<Unit> groups = new ArrayList<>(2);
        final int middle = (units.size() + 1) / 2;
        if (middle > 0) {
            groups.add(new Unit(units.get(0).start(), units.get(middle - 1).end(), units.get(0).timeMs()));
        }
        if (middle < units.size()) {
            groups.add(new Unit(units.get(middle).start(), units.get(units.size() - 1).end(),
                    units.get(middle).timeMs()));
        }
        final List<Chunk> result = new ArrayList<>(groups.size());
        final int total = Math.max(1, characterCount(text));
        long previousTime = line.startTimeMs();
        for (int i = 0; i < groups.size(); i++) {
            final Unit group = groups.get(i);
            long start = group.timeMs();
            if (i == 0) start = line.startTimeMs();
            else if (start < line.startTimeMs() || start < previousTime) {
                start = intervalEndMs > line.startTimeMs() && line.startTimeMs() >= 0
                        ? line.startTimeMs() + (intervalEndMs - line.startTimeMs())
                          * characterCount(text.substring(0, group.start())) / total
                        : -1;
            }
            if (start >= 0) previousTime = start;
            final String formatted = LyricsTextFormatter.format(
                    text.substring(group.start(), group.end()), capitalize, strip).text();
            result.add(new Chunk(PairedPunctuation.restore(text, formatted, group.start(), group.end()), start));
        }
        return List.copyOf(result);
    }

    /** A negative offset looks ahead, so -600 shows the next line 600 ms early. */
    public static long selectionPosition(long playbackMs, int offsetMs) {
        return Math.max(0, playbackMs - (long) offsetMs);
    }

    /**
     * The frame of the instrumental template at the position. Each line of the template is one
     * frame, and the frames share the gap duration equally.
     */
    public static String instrumental(@Nullable String template, long startMs, long endMs, long positionMs,
                                      @Nullable String title, @Nullable String artist, @Nullable String lastLyric) {
        final String[] frames = (template == null ? DEFAULT_INSTRUMENTAL : template).split("\\R", -1);
        final long duration = endMs - startMs;
        final int index = endMs == Long.MAX_VALUE || duration <= 0 ? 0
                : (int) Math.min(frames.length - 1,
                        Math.max(0, positionMs - startMs) / (double) duration * frames.length);
        return replacePlaceholders(frames[index], title, artist, lastLyric == null ? "" : lastLyric);
    }

    private static List<Unit> timedUnits(LyricsLine line, LyricsTextFormatter.Result cleaned) {
        if (!line.hasWordTimings()) return List.of();
        final List<Unit> units = new ArrayList<>();
        int cursor = 0;
        final boolean spaced = hasWhitespace(line.text());
        for (Word word : line.words()) {
            final String own = word.text().trim();
            if (own.isEmpty()) continue;
            final int start = line.text().indexOf(own, cursor);
            if (start < 0 || word.startMs() < 0) return List.of();
            // Unrepresented punctuation belongs to the previous unit; missing words require fallback.
            if (hasLetterOrDigit(line.text().substring(cursor, start))) {
                return List.of();
            }
            final int end = start + own.length();
            final Unit next = new Unit(cleaned.offset(start), cleaned.offset(end), word.startMs());
            if (!units.isEmpty() && spaced && !hasWhitespace(line.text().substring(cursor, start))) {
                Unit previous = units.remove(units.size() - 1);
                units.add(new Unit(previous.start(), next.end(), previous.timeMs()));
            } else if (next.end() > next.start()) {
                units.add(next);
            }
            cursor = end;
        }
        // A partially aligned word list must not silently discard any lyric content.
        if (units.isEmpty()) return List.of();
        if (hasLetterOrDigit(cleaned.text().substring(0, units.get(0).start()))
                || hasLetterOrDigit(cleaned.text().substring(units.get(units.size() - 1).end()))) {
            return List.of();
        }
        final List<Unit> covering = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            final Unit unit = units.get(i);
            int end = i + 1 < units.size() ? units.get(i + 1).start() : cleaned.text().length();
            while (end > unit.end()) {
                final int cp = cleaned.text().codePointBefore(end);
                if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) break;
                end -= Character.charCount(cp);
            }
            covering.add(new Unit(i == 0 ? 0 : unit.start(), end, unit.timeMs()));
        }
        return covering;
    }

    private static boolean hasLetterOrDigit(String text) {
        return text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean hasWhitespace(String text) {
        return text.codePoints().anyMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    }

    private static List<Unit> untimedUnits(String text) {
        final List<Unit> units = new ArrayList<>();
        if (!hasWhitespace(text)) {
            final Matcher matcher = GRAPHEME.matcher(text);
            while (matcher.find()) units.add(new Unit(matcher.start(), matcher.end(), -1));
            return units;
        }
        int start = -1;
        for (int at = 0; at < text.length();) {
            final int cp = text.codePointAt(at);
            final boolean whitespace = Character.isWhitespace(cp) || Character.isSpaceChar(cp);
            if (!whitespace && start < 0) start = at;
            if (whitespace && start >= 0) {
                units.add(new Unit(start, at, -1));
                start = -1;
            }
            at += Character.charCount(cp);
        }
        if (start >= 0) units.add(new Unit(start, text.length(), -1));
        return units;
    }
}

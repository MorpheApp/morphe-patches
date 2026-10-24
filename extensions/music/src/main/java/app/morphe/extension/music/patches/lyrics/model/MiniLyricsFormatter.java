/*
 * Copyright 2026 Morphe contributors.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.patches.lyrics.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared MiniPlayer/MediaSession text selection. Does not alter source lyrics or timing. */
public final class MiniLyricsFormatter {
    public static final String DEFAULT_TRACK_FORMAT = "{t} - {a}";
    public static final String DEFAULT_ENCAPSULATION = "{lrc} :(x{n}):";
    public static final String DEFAULT_INSTRUMENTAL = "♪\n♪♪\n♪♪♪";
    // Android's ICU regex engine (API 26+) and the host JVM both implement extended graphemes.
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    public record Chunk(String text, long startMs) { }
    private record Unit(int start, int end, long timeMs) { }

    private MiniLyricsFormatter() { }

    public static String migratedTrackFormat(String explicitFormat, boolean artistFirst) {
        return explicitFormat != null ? explicitFormat : artistFirst ? "{a} - {t}" : DEFAULT_TRACK_FORMAT;
    }

    public static boolean validEncapsulation(String template) {
        return template != null && template.contains("{lrc}");
    }

    /** Single-pass substitution: placeholders inside song names are never interpreted. */
    public static String trackLabel(String template, String title, String artist) {
        final String pattern = template == null ? DEFAULT_TRACK_FORMAT : template;
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length();) {
            if (pattern.startsWith("{t}", i)) {
                out.append(title == null ? "" : title); i += 3;
            } else if (pattern.startsWith("{a}", i)) {
                out.append(artist == null ? "" : artist); i += 3;
            } else out.append(pattern.charAt(i++));
        }
        return out.toString();
    }

    public static String encapsulate(String template, String lyric) {
        return encapsulate(template, lyric, 1);
    }

    /**
     * {lrc} is the lyric and {n} its repeat count. Text between unescaped colons appears only when
     * the count is above 1; a backslash before a colon keeps it literal.
     */
    public static String encapsulate(String template, String lyric, int repeat) {
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
        return out.toString().replace("{n}", String.valueOf(repeat)).replace("{lrc}", lyric);
    }

    /** Consecutive identical lyric lines ending at index; the first line of a run is 1. */
    public static int repeatCount(List<LyricsLine> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size() || lines.get(index) == null) return 1;
        final String text = lines.get(index).text();
        int count = 1;
        for (int j = index - 1; j >= 0; j--) {
            final LyricsLine previous = lines.get(j);
            if (previous == null || previous.text() == null || !previous.text().equals(text)) break;
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

    public static String current(List<Chunk> chunks, long positionMs, String wrapper) {
        return current(chunks, positionMs, wrapper, 1);
    }

    public static String current(List<Chunk> chunks, long positionMs, String wrapper, int repeat) {
        if (chunks.isEmpty()) return "";
        Chunk selected = chunks.get(0);
        for (int i = 1; i < chunks.size(); i++) {
            Chunk candidate = chunks.get(i);
            if (candidate.startMs() < 0 || candidate.startMs() > positionMs) break;
            selected = candidate;
        }
        return selected.text().isEmpty() ? "" : encapsulate(wrapper, selected.text(), repeat);
    }

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
        if (middle > 0) groups.add(new Unit(units.get(0).start(), units.get(middle - 1).end(), units.get(0).timeMs()));
        if (middle < units.size()) groups.add(new Unit(units.get(middle).start(), units.get(units.size() - 1).end(), units.get(middle).timeMs()));
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

    /** Negative offsets look ahead, so e.g. -600 sends the next line early. */
    public static long selectionPosition(long playbackMs, int offsetMs) {
        return Math.max(0, playbackMs - (long) offsetMs);
    }

    // no extra timer: seeking and pause use the same playback position.
    public static String instrumental(String template, long startMs, long endMs, long positionMs,
                                      String title, String artist, String lastLyric) {
        final String[] frames = (template == null ? DEFAULT_INSTRUMENTAL : template).split("\\R", -1);
        final long duration = endMs - startMs;
        final int index = endMs == Long.MAX_VALUE || duration <= 0 ? 0
                : (int) Math.min(frames.length - 1,
                        Math.max(0, positionMs - startMs) / (double) duration * frames.length);
        final String pattern = frames[index];
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < pattern.length();) {
            if (pattern.startsWith("{t}", i)) { text.append(title == null ? "" : title); i += 3; }
            else if (pattern.startsWith("{a}", i)) { text.append(artist == null ? "" : artist); i += 3; }
            else if (pattern.startsWith("{lrc}", i)) { text.append(lastLyric == null ? "" : lastLyric); i += 5; }
            else text.append(pattern.charAt(i++));
        }
        return text.toString();
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
            if (line.text().substring(cursor, start).codePoints().anyMatch(Character::isLetterOrDigit)) {
                return List.of();
            }
            final int end = start + own.length();
            final Unit next = new Unit(cleaned.offset(start), cleaned.offset(end), word.startMs());
            if (!units.isEmpty() && spaced && !hasWhitespace(line.text().substring(cursor, start))) {
                Unit previous = units.remove(units.size() - 1);
                units.add(new Unit(previous.start(), next.end(), previous.timeMs()));
            } else if (next.end() > next.start()) units.add(next);
            cursor = end;
        }
        // A partially aligned word list must not silently discard any lyric content.
        if (units.isEmpty()) return List.of();
        if (cleaned.text().substring(0, units.get(0).start()).codePoints().anyMatch(Character::isLetterOrDigit)
                || cleaned.text().substring(units.get(units.size() - 1).end()).codePoints().anyMatch(Character::isLetterOrDigit)) {
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
                units.add(new Unit(start, at, -1)); start = -1;
            }
            at += Character.charCount(cp);
        }
        if (start >= 0) units.add(new Unit(start, text.length(), -1));
        return units;
    }
}

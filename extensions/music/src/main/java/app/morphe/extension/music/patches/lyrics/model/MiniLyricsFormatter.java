/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * East Asian Width data: Unicode 17.0 EastAsianWidth.txt, W/F properties.
 * https://www.unicode.org/Public/17.0.0/ucd/EastAsianWidth.txt
 *
 * UNICODE LICENSE V3
 *
 * COPYRIGHT AND PERMISSION NOTICE
 *
 * Copyright (C) 1991-2026 Unicode, Inc.
 *
 * NOTICE TO USER: Carefully read the following legal agreement. BY
 * DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
 * SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
 * TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
 * DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of data files and any associated documentation (the "Data Files") or
 * software and any associated documentation (the "Software") to deal in the
 * Data Files or Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, and/or sell
 * copies of the Data Files or Software, and to permit persons to whom the
 * Data Files or Software are furnished to do so, provided that either (a)
 * this copyright and permission notice appear with all copies of the Data
 * Files or Software, or (b) this copyright and permission notice appear in
 * associated Documentation.
 *
 * THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
 * KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
 * THIRD PARTY RIGHTS.
 *
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
 * BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
 * OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
 * WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
 * ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
 * FILES OR SOFTWARE.
 *
 * Except as contained in this notice, the name of a copyright holder shall
 * not be used in advertising or otherwise to promote the sale, use or other
 * dealings in these Data Files or Software without prior written
 * authorization of the copyright holder.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text of the miniplayer and the media session. The lyrics themselves are never changed. */
public final class MiniLyricsFormatter {
    public static final String DEFAULT_TRACK_FORMAT = "{t} :({v}): - {a}";
    public static final String ARTIST_FIRST_TRACK_FORMAT = "{a} - {t} :({v}):";
    public static final String DEFAULT_ENCAPSULATION = "{lrc} :(x{n}):";
    public static final String DEFAULT_INSTRUMENTAL = "♪\n♪♪\n♪♪♪";
    private static final String LYRIC_PLACEHOLDER = "{lrc}";
    private static final String VARIANT_PLACEHOLDER = "{v}";
    /** Placeholder names, written inside braces in a template. */
    private static final String[] PLACEHOLDERS = {"lrc", "t", "a", "v", "n"};
    /** Marks where a line without spaces was split. */
    public static final String SPLIT_ELLIPSIS = "…";
    /** How many times a track format line is halved at most. */
    private static final int MAX_FRAME_SPLIT_DEPTH = 6;
    /** An extended grapheme cluster, so a character with combining marks or an emoji is not split. */
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern REPEATED_SPACES = Pattern.compile("[ \\t\\u00A0]{2,}");

    public record Chunk(String text, long startMs) {
    }

    private record Unit(int start, int end, long timeMs) {
    }

    private MiniLyricsFormatter() {
    }

    public static boolean validEncapsulation(@Nullable String template) {
        return template != null && template.contains(LYRIC_PLACEHOLDER);
    }

    /** If the track format shows the version, which then has to be looked up. */
    public static boolean usesVariant(@Nullable String template) {
        return (template == null ? DEFAULT_TRACK_FORMAT : template).contains(VARIANT_PLACEHOLDER);
    }

    public static String trackLabel(@Nullable String template, @Nullable String title, @Nullable String artist) {
        return trackLabel(template, title, artist, null);
    }

    /**
     * {t} is the title, {a} the artist and {v} the variant, such as Acoustic. The variant is only
     * known when the backend reports it separately from the title, and is never parsed out of a
     * title. Text between unescaped colons only appears when every placeholder in it has a value.
     * Each line of the template is one frame of the artist line.
     */
    public static String trackLabel(@Nullable String template, @Nullable String title,
                                    @Nullable String artist, @Nullable String variant) {
        Map<String, String> values = new HashMap<>();
        values.put("t", title == null ? "" : title);
        values.put("a", artist == null ? "" : artist);
        values.put("v", variant == null ? "" : variant.trim());
        String rendered = render(template == null ? DEFAULT_TRACK_FORMAT : template, values, true);
        StringBuilder out = new StringBuilder();
        for (String line : LINE_BREAK.split(rendered, -1)) {
            // A hidden section leaves the spaces around it behind.
            String tidy = REPEATED_SPACES.matcher(line).replaceAll(" ").trim();
            if (tidy.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(tidy);
        }
        return out.toString();
    }

    /**
     * The frames the artist line cycles through: one per template line, and a line wider than the
     * limit is split into frames the same way a long lyric is. The split is decided from the whole
     * rendered line, not from any single placeholder.
     */
    public static List<String> trackFrames(@Nullable String template, @Nullable String title,
                                           @Nullable String artist, @Nullable String variant,
                                           int limit, double fullWidthMultiplier) {
        List<String> frames = new ArrayList<>();
        String label = trackLabel(template, title, artist, variant);
        for (String line : label.split("\n")) {
            if (line.isEmpty()) continue;
            if (limit <= 0 || width(line, fullWidthMultiplier) <= limit) {
                frames.add(line);
                continue;
            }
            splitFrame(line, limit, fullWidthMultiplier, frames, 0);
        }
        if (frames.isEmpty()) frames.add("");
        return List.copyOf(frames);
    }

    /** Halves a frame the way a long lyric is halved, again and again until every part fits. */
    private static void splitFrame(String frame, int limit, double fullWidthMultiplier,
                                   List<String> frames, int depth) {
        if (depth >= MAX_FRAME_SPLIT_DEPTH || width(frame, fullWidthMultiplier) <= limit) {
            frames.add(frame);
            return;
        }
        List<Chunk> halves = chunks(new LyricsLine(0, frame), -1, limit, false, false, fullWidthMultiplier);
        if (halves.size() < 2) {
            frames.add(frame);
            return;
        }
        for (Chunk half : halves) {
            String text = half.text();
            if (text.isEmpty()) continue;
            // An ellipsis added at a split does not count against the limit, or a two-character
            // part would never fit a limit of two.
            String bare = text.endsWith(SPLIT_ELLIPSIS)
                    ? text.substring(0, text.length() - SPLIT_ELLIPSIS.length()) : text;
            if (bare.equals(frame) || width(bare, fullWidthMultiplier) <= limit) {
                frames.add(text);
                continue;
            }
            final int before = frames.size();
            splitFrame(bare, limit, fullWidthMultiplier, frames, depth + 1);
            if (!bare.equals(text) && frames.size() > before) {
                final int last = frames.size() - 1;
                String lastFrame = frames.get(last);
                if (!lastFrame.endsWith(SPLIT_ELLIPSIS)) frames.set(last, lastFrame + SPLIT_ELLIPSIS);
            }
        }
    }

    /**
     * Replaces {t} with the title, {a} with the artist and {lrc} with the lyric if one is given.
     * This is done in one pass, so a placeholder in a song title is kept as is.
     */
    private static String replacePlaceholders(String template, @Nullable String title,
                                              @Nullable String artist, @Nullable String lyric) {
        StringBuilder out = new StringBuilder();
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
        String source = validEncapsulation(template) ? template : DEFAULT_ENCAPSULATION;
        Map<String, String> values = new HashMap<>();
        values.put("lrc", lyric);
        values.put("n", String.valueOf(repeat));
        return render(source, values, repeat > 1);
    }

    /**
     * Fills in placeholders in one pass, so a placeholder inside a value is kept as is. Text between
     * unescaped colons appears only when every placeholder in it is populated: a non-empty value,
     * and for the count {n} a value other than 1. A section without placeholders appears when
     * {@code bareShown} is set. A backslash before a colon keeps it literal.
     */
    static String render(String template, Map<String, String> values, boolean bareShown) {
        StringBuilder out = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean inside = false;
        boolean populated = true;
        boolean placeholders = false;
        for (int i = 0, length = template.length(); i < length; ) {
            final char c = template.charAt(i);
            if (c == '\\' && i + 1 < length && template.charAt(i + 1) == ':') {
                segment.append(':');
                i += 2;
                continue;
            }
            if (c == ':') {
                if (!inside || (placeholders ? populated : bareShown)) out.append(segment);
                segment.setLength(0);
                inside = !inside;
                populated = true;
                placeholders = false;
                i++;
                continue;
            }
            String name = c == '{' ? placeholderAt(template, i, values) : null;
            if (name != null) {
                String value = values.get(name);
                if (inside) {
                    placeholders = true;
                    if (!isPopulated(name, value)) populated = false;
                }
                segment.append(value == null ? "" : value);
                i += name.length() + 2;
                continue;
            }
            segment.append(c);
            i++;
        }
        if (!inside || (placeholders ? populated : bareShown)) out.append(segment);
        return out.toString();
    }

    @Nullable
    private static String placeholderAt(String template, int index, Map<String, String> values) {
        for (String name : PLACEHOLDERS) {
            if (values.containsKey(name) && template.startsWith("{" + name + "}", index)) return name;
        }
        return null;
    }

    private static boolean isPopulated(String name, @Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        return !"n".equals(name) || !value.equals("1");
    }

    /** How many times in a row the line at the index is sung, counting up to and including it. */
    public static int repeatCount(List<LyricsLine> lines, int index) {
        String text = lines.get(index).text();
        int count = 1;
        for (int i = index - 1; i >= 0 && lines.get(i).text().equals(text); i--) {
            count++;
        }
        return count;
    }

    /**
     * The width of the text against a character limit: a full-width character, such as a CJK
     * ideograph or kana, counts as the multiplier and any other character as 1.
     */
    public static double width(String text, double fullWidthMultiplier) {
        final double wide = Double.isFinite(fullWidthMultiplier) && fullWidthMultiplier > 0
                ? fullWidthMultiplier : 1;
        Matcher matcher = GRAPHEME.matcher(text);
        double total = 0;
        while (matcher.find()) total += isFullWidth(text.codePointAt(matcher.start())) ? wide : 1;
        return total;
    }

    /** Unicode 17 EastAsianWidth.txt W/F properties, merged into inclusive ranges.
     * https://www.unicode.org/Public/17.0.0/ucd/EastAsianWidth.txt
     * Distributed under Unicode License V3 (see the file header).
     */
    private static final int[] FULL_WIDTH_RANGES = {
            0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC,
            0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
            0x2630, 0x2637, 0x2648, 0x2653, 0x267F, 0x267F, 0x268A, 0x268F,
            0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
            0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA,
            0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD,
            0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
            0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
            0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50,
            0x2B55, 0x2B55, 0x2E80, 0x2E99, 0x2E9B, 0x2EF3, 0x2F00, 0x2FD5,
            0x2FF0, 0x303E, 0x3041, 0x3096, 0x3099, 0x30FF, 0x3105, 0x312F,
            0x3131, 0x318E, 0x3190, 0x31E5, 0x31EF, 0x321E, 0x3220, 0x3247,
            0x3250, 0xA48C, 0xA490, 0xA4C6, 0xA960, 0xA97C, 0xAC00, 0xD7A3,
            0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE52, 0xFE54, 0xFE66,
            0xFE68, 0xFE6B, 0xFF01, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE4,
            0x16FF0, 0x16FF6, 0x17000, 0x18CD5, 0x18CFF, 0x18D1E, 0x18D80, 0x18DF2,
            0x1AFF0, 0x1AFF3, 0x1AFF5, 0x1AFFB, 0x1AFFD, 0x1AFFE, 0x1B000, 0x1B122,
            0x1B132, 0x1B132, 0x1B150, 0x1B152, 0x1B155, 0x1B155, 0x1B164, 0x1B167,
            0x1B170, 0x1B2FB, 0x1D300, 0x1D356, 0x1D360, 0x1D376, 0x1F004, 0x1F004,
            0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F202,
            0x1F210, 0x1F23B, 0x1F240, 0x1F248, 0x1F250, 0x1F251, 0x1F260, 0x1F265,
            0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393,
            0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4,
            0x1F3F8, 0x1F43E, 0x1F440, 0x1F440, 0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D,
            0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596,
            0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC,
            0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D8, 0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC,
            0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A,
            0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FA8A,
            0x1FA8E, 0x1FAC6, 0x1FAC8, 0x1FAC8, 0x1FACD, 0x1FADC, 0x1FADF, 0x1FAEA,
            0x1FAEF, 0x1FAF8, 0x20000, 0x2FFFD, 0x30000, 0x3FFFD,
    };

    static boolean isFullWidth(int cp) {
        int lo = 0, hi = FULL_WIDTH_RANGES.length / 2 - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (cp < FULL_WIDTH_RANGES[mid * 2]) hi = mid - 1;
            else if (cp > FULL_WIDTH_RANGES[mid * 2 + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }

    public static int characterCount(String text) {
        Matcher matcher = GRAPHEME.matcher(text);
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
        return chunks(line, intervalEndMs, limit, capitalize, strip, 1);
    }

    /**
     * Splits by the word times when the provider has them, otherwise by ICU word boundaries. A line
     * without spaces that is split gets an ellipsis at the split.
     */
    public static List<Chunk> chunks(LyricsLine line, long intervalEndMs, int limit,
                                     boolean capitalize, boolean strip, double fullWidthMultiplier) {
        LyricsTextFormatter.Result cleaned = LyricsTextFormatter.format(line.text(), false, strip);
        String text = cleaned.text();
        if (limit <= 0 || width(text, fullWidthMultiplier) <= limit) {
            return List.of(new Chunk(LyricsTextFormatter.format(text, capitalize, false).text(),
                    line.startTimeMs()));
        }
        List<Unit> timed = timedUnits(line, cleaned);
        List<Unit> units = timed.isEmpty() ? untimedUnits(text) : timed;
        List<Unit> groups = new ArrayList<>(2);
        final int middle = (units.size() + 1) / 2;
        if (middle > 0) {
            groups.add(new Unit(units.get(0).start(), units.get(middle - 1).end(), units.get(0).timeMs()));
        }
        if (middle < units.size()) {
            groups.add(new Unit(units.get(middle).start(), units.get(units.size() - 1).end(),
                    units.get(middle).timeMs()));
        }
        List<Chunk> result = new ArrayList<>(groups.size());
        final int total = Math.max(1, characterCount(text));
        long previousTime = line.startTimeMs();
        for (int i = 0; i < groups.size(); i++) {
            Unit group = groups.get(i);
            long start = group.timeMs();
            if (i == 0) start = line.startTimeMs();
            else if (start < line.startTimeMs() || start < previousTime) {
                start = intervalEndMs > line.startTimeMs() && line.startTimeMs() >= 0
                        ? line.startTimeMs() + (intervalEndMs - line.startTimeMs())
                          * characterCount(text.substring(0, group.start())) / total
                        : -1;
            }
            if (start >= 0) previousTime = start;
            String formatted = LyricsTextFormatter.format(
                    text.substring(group.start(), group.end()), capitalize, strip).text();
            // The ellipsis marks the split itself, so it goes inside any quotes closed around the part.
            if (i + 1 < groups.size() && !hasWhitespace(text) && !formatted.endsWith(SPLIT_ELLIPSIS)) {
                formatted += SPLIT_ELLIPSIS;
            }
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
        String[] frames = LINE_BREAK.split(template == null ? DEFAULT_INSTRUMENTAL : template, -1);
        final long duration = endMs - startMs;
        final int index = endMs == Long.MAX_VALUE || duration <= 0 ? 0
                : (int) Math.min(frames.length - 1,
                        Math.max(0, positionMs - startMs) / (double) duration * frames.length);
        return replacePlaceholders(frames[index], title, artist, lastLyric == null ? "" : lastLyric);
    }

    private static List<Unit> timedUnits(LyricsLine line, LyricsTextFormatter.Result cleaned) {
        if (!line.hasWordTimings()) return List.of();
        List<Unit> units = new ArrayList<>();
        int cursor = 0;
        final boolean spaced = hasWhitespace(line.text());
        for (Word word : line.words()) {
            String own = word.text().trim();
            if (own.isEmpty()) continue;
            final int start = line.text().indexOf(own, cursor);
            if (start < 0 || word.startMs() < 0) return List.of();
            // Unrepresented punctuation belongs to the previous unit; missing words require fallback.
            if (hasLetterOrDigit(line.text().substring(cursor, start))) {
                return List.of();
            }
            final int end = start + own.length();
            Unit next = new Unit(cleaned.offset(start), cleaned.offset(end), word.startMs());
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
        List<Unit> covering = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            int end = i + 1 < units.size() ? units.get(i + 1).start() : cleaned.text().length();
            while (end > unit.end()) {
                final int cp = cleaned.text().codePointBefore(end);
                if (!isWhitespace(cp)) break;
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
        return text.codePoints().anyMatch(MiniLyricsFormatter::isWhitespace);
    }

    /**
     * Word units from the ICU word boundaries. In text with spaces a unit runs from space to space,
     * so punctuation and hyphenated words stay whole; in text without spaces, such as Japanese, the
     * dictionary words are the units. Punctuation joins the word before it.
     */
    private static List<Unit> untimedUnits(String text) {
        List<Unit> units = new ArrayList<>();
        final boolean spaced = hasWhitespace(text);
        BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
        words.setText(text);
        int start = words.first();
        // Opening punctuation is held back and joins the word after it.
        int opening = -1;
        int worded = 0;
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String piece = text.substring(start, end);
            if (!hasNonWhitespace(piece)) continue;
            final boolean letters = hasLetterOrDigit(piece);
            final boolean joined = start > 0 && !isWhitespace(text.codePointBefore(start));
            if (!letters && isOpening(piece) && !(spaced && joined && !units.isEmpty())) {
                if (opening < 0) opening = start;
                continue;
            }
            final int from = opening >= 0 ? opening : start;
            opening = -1;
            if (!units.isEmpty() && joined && from == start && (spaced || !letters)) {
                Unit previous = units.remove(units.size() - 1);
                units.add(new Unit(previous.start(), end, -1));
            } else {
                units.add(new Unit(from, end, -1));
                if (letters) worded++;
            }
        }
        if (opening >= 0) {
            if (units.isEmpty()) units.add(new Unit(opening, text.length(), -1));
            else {
                Unit previous = units.remove(units.size() - 1);
                units.add(new Unit(previous.start(), text.length(), -1));
            }
        }
        if (worded < 2 && !spaced) {
            // One dictionary word longer than the limit: fall back to characters.
            units.clear();
            Matcher matcher = GRAPHEME.matcher(text);
            while (matcher.find()) units.add(new Unit(matcher.start(), matcher.end(), -1));
        }
        return units;
    }

    private static boolean isOpening(String piece) {
        return piece.codePoints().allMatch(cp -> {
            final int type = Character.getType(cp);
            return type == Character.START_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || cp == '"' || cp == '\'' || cp == '„' || cp == '‚' || cp == '¿' || cp == '¡';
        });
    }

    private static boolean isWhitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean hasNonWhitespace(String text) {
        return text.codePoints().anyMatch(cp -> !isWhitespace(cp));
    }
}

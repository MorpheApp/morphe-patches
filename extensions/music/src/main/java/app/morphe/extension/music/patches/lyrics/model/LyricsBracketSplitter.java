/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class LyricsBracketSplitter {

    private static final LyricsLine BLANK = new LyricsLine(LyricsLine.NO_TIME, "");

    private static final Pattern NOT_SUNG_RUN = Pattern.compile(
            "(?i)\\s*(?:x\\s*\\d+|\\d+\\s*x|repeat|chorus|verse|pre\\s*chorus|post\\s*chorus)\\s*");

    private record Run(int open, int close) {
    }

    private record Cut(LyricsLine sung, LyricsLine background) {
    }

    private LyricsBracketSplitter() {
    }

    public static Lyrics apply(Lyrics lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return lyrics;
        }

        final List<LyricsLine> lines = lyrics.lines();
        final int count = lines.size();
        final int[] room = new int[count];
        int extra = 0;
        for (int i = 0; i < count; i++) {
            room[i] = cut(lines, i) == null ? 1 : 2;
            extra += room[i] - 1;
        }
        if (extra == 0) {
            return lyrics;
        }

        final List<LyricsLine> split = new ArrayList<>(count + extra);
        for (int i = 0; i < count; i++) {
            final Cut cut = cut(lines, i);
            if (cut == null) {
                split.add(lines.get(i));
            } else {
                split.add(cut.sung());
                split.add(cut.background());
            }
        }

        return new Lyrics(split, lyrics.providerName(), lyrics.synced(),
                widen(lyrics.romanization(), count, room),
                widen(lyrics.translations(), count, room),
                widen(lyrics.romanizations(), count, room),
                lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                lyrics.sourceUrl(), lyrics.about());
    }

    /** Reading brackets on top of a stated split would put two background lines under one sung line. */
    @Nullable
    private static Cut cut(List<LyricsLine> lines, int index) {
        final LyricsLine line = lines.get(index);
        if (line.isBG() || line.text().isBlank()) {
            return null;
        }
        if (index + 1 < lines.size() && lines.get(index + 1).isBG()) {
            return null;
        }
        return line.hasWords() ? cutWordTimed(line) : cutLineTimed(line);
    }

    @Nullable
    private static Cut cutWordTimed(LyricsLine line) {
        final String text = line.text();
        final List<Word> words = line.words();
        // The renderer reads the words back out of the line text, so the two have to cover the
        // same characters for the offsets below to mean anything.
        if (lengthOf(words) != text.length()) {
            return null;
        }

        final Run head = headRun(text);
        final Run tail = tailRun(text);
        if (head == null && tail == null) {
            return null;
        }
        final int sungFrom = head == null ? 0 : head.close() + 1;
        final int sungTo = tail == null ? text.length() : tail.open();
        if (sungFrom >= sungTo || !sung(text.substring(sungFrom, sungTo))
                || (head != null && !sung(text.substring(head.open(), head.close() + 1)))
                || (tail != null && !sung(text.substring(tail.open(), tail.close() + 1)))) {
            return null;
        }

        final List<Word> sung = new ArrayList<>(words.size());
        final List<Word> background = new ArrayList<>(words.size());
        int offset = 0;
        for (Word word : words) {
            final int from = offset;
            final int to = offset + word.text().length();
            offset = to;

            final int inSung = Math.max(0, Math.min(to, sungTo) - Math.max(from, sungFrom));
            int inBackground = 0;
            if (head != null) {
                inBackground += Math.max(0, Math.min(to, head.close() + 1) - from);
            }
            if (tail != null) {
                inBackground += Math.max(0, to - Math.max(from, tail.open()));
            }

            if (inSung <= 0 && inBackground <= 0) {
                continue;
            }
            // Halving a straddling word would cost it its timing, so it goes whole to the bigger side.
            if (inSung > inBackground || (inSung == inBackground && inSung > 0)) {
                sung.add(word);
            } else {
                background.add(word);
            }
        }

        return finish(line, sung, background);
    }

    /** Both halves keep the line's one timing, there being none to divide. */
    @Nullable
    private static Cut cutLineTimed(LyricsLine line) {
        final String text = line.text();

        final Run head = headRun(text);
        final Run tail = tailRun(text);
        if (head == null && tail == null) {
            return null;
        }

        final int sungFrom = head == null ? 0 : head.close() + 1;
        final int sungTo = tail == null ? text.length() : tail.open();
        if (sungFrom >= sungTo) {
            return null;
        }

        final StringBuilder backgroundText = new StringBuilder();
        if (head != null) {
            backgroundText.append(text, head.open() + 1, head.close());
        }
        if (tail != null) {
            if (backgroundText.length() > 0) {
                backgroundText.append(' ');
            }
            backgroundText.append(text, tail.open() + 1, tail.close());
        }

        return finishText(line, text.substring(sungFrom, sungTo), backgroundText.toString());
    }

    @Nullable
    private static Cut finish(LyricsLine line, List<Word> sung, List<Word> background) {
        if (sung.isEmpty() || background.isEmpty()) {
            return null;
        }
        final List<Word> trimmed = trimBrackets(sung);
        final List<Word> trimmedBackground = trimBrackets(background);
        final String sungText = textOf(trimmed);
        final String backgroundText = textOf(trimmedBackground);
        if (sungText.isBlank() || backgroundText.isBlank()) {
            return null;
        }
        return new Cut(plain(line, sungText, trimmed, false),
                plain(line, backgroundText, trimmedBackground, true));
    }

    @Nullable
    private static Cut finishText(LyricsLine line, String sungText, String backgroundText) {
        final String kept = sungText.trim();
        if (kept.isEmpty() || !sung(backgroundText)) {
            return null;
        }
        return new Cut(plain(line, kept, List.of(), false),
                plain(line, backgroundText.trim(), List.of(), true));
    }

    private static LyricsLine plain(LyricsLine line, String text, List<Word> words, boolean isBG) {
        return new LyricsLine(line.startTimeMs(), line.endTimeMs(), text, words,
                line.agentId(), line.isDuet(), isBG, line.songPart());
    }

    /** Whitespace goes first and last too, or a bracket touching the text is never reached. */
    private static List<Word> trimBrackets(List<Word> words) {
        final List<Word> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            String body = words.get(i).text();
            if (i == 0) {
                body = body.stripLeading();
                while (isOpen(body.charAt(0))) {
                    body = body.substring(1).stripLeading();
                }
            }
            if (i == words.size() - 1) {
                body = body.stripTrailing();
                while (!body.isEmpty() && isClose(body.charAt(body.length() - 1))) {
                    body = body.substring(0, body.length() - 1).stripTrailing();
                }
            }
            if (body.isEmpty()) {
                continue;
            }
            final Word word = words.get(i);
            out.add(new Word(word.startMs(), word.endMs(), body,
                    word.romaji(), body.endsWith(" ")));
        }
        return out;
    }

    /** A run running straight into a word is not a vocal of its own. */
    @Nullable
    private static Run headRun(String text) {
        int open = 0;
        while (open < text.length() && Character.isWhitespace(text.charAt(open))) {
            open++;
        }
        if (open >= text.length() || !isOpen(text.charAt(open))) {
            return null;
        }
        final Run run = match(text, open, text.length(), true);
        if (run == null) {
            return null;
        }
        return isWide(text.charAt(run.open()))
                || run.close() + 1 == text.length()
                || Character.isWhitespace(text.charAt(run.close() + 1)) ? run : null;
    }

    @Nullable
    private static Run tailRun(String text) {
        int close = text.length();
        while (close > 0 && Character.isWhitespace(text.charAt(close - 1))) {
            close--;
        }
        if (close == 0 || !isClose(text.charAt(close - 1))) {
            return null;
        }
        final Run run = match(text, close - 1, 0, false);
        if (run == null) {
            return null;
        }
        return isWide(text.charAt(run.open()))
                || run.open() == 0
                || Character.isWhitespace(text.charAt(run.open() - 1)) ? run : null;
    }

    /** A full-width bracket stands on a cell of its own, so it needs no space around it. */
    private static boolean isWide(char c) {
        return c == '（' || c == '）';
    }

    /** Nesting is counted so an inner bracket does not close the run early; "(oh]" is no run at all. */
    @Nullable
    private static Run match(String text, int from, int toward, boolean forward) {
        final char first = text.charAt(from);
        final char opener = forward ? first : openerOf(first);
        final char closer = forward ? closerOf(first) : first;
        // Scanning back starts on the closing bracket, so that one is the deeper side.
        final char deeper = forward ? opener : closer;
        final char shallower = forward ? closer : opener;
        final int step = forward ? 1 : -1;
        int depth = 0;
        for (int i = from; i != toward; i += step) {
            final char c = text.charAt(i);
            if (c == deeper) {
                depth++;
            } else if (c == shallower) {
                depth--;
                if (depth == 0) {
                    return forward ? new Run(from, i) : new Run(i, from);
                }
            }
        }
        return null;
    }

    /** Round brackets only: a square bracket or a brace is far more likely to be quoting. */
    private static boolean isOpen(char c) {
        return closerOf(c) != 0;
    }

    private static boolean isClose(char c) {
        return openerOf(c) != 0;
    }

    private static char closerOf(char open) {
        switch (open) {
            case '(': return ')';
            case '（': return '）';
            default: return 0;
        }
    }

    private static char openerOf(char close) {
        switch (close) {
            case ')': return '(';
            case '）': return '（';
            default: return 0;
        }
    }

    /** A repeat count or section name is bracketed too, but it is not sung. */
    private static boolean sung(String text) {
        if (text == null) {
            return false;
        }
        final String body = text.trim();
        if (body.isEmpty() || NOT_SUNG_RUN.matcher(body).matches()) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            if (Character.isLetter(body.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(List<Word> words) {
        final StringBuilder text = new StringBuilder();
        for (Word word : words) {
            text.append(word.text());
        }
        return text.toString();
    }

    private static int lengthOf(List<Word> words) {
        int total = 0;
        for (Word word : words) {
            total += word.text().length();
        }
        return total;
    }

    /** A track written against other lines stays as it is: shorter than the lyrics is the mismatch
 *  the readers already check for before trusting one. */
    @Nullable
    private static List<LyricsLine> widen(@Nullable List<LyricsLine> aligned, int count,
                                          int[] room) {
        if (aligned == null || aligned.isEmpty() || aligned.size() != count) {
            return aligned;
        }
        final List<LyricsLine> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(aligned.get(i));
            for (int slot = 1; slot < room[i]; slot++) {
                out.add(BLANK);
            }
        }
        return out;
    }

    @Nullable
    private static Map<String, List<LyricsLine>> widen(@Nullable Map<String, List<LyricsLine>> tracks,
                                                       int count, int[] room) {
        if (tracks == null || tracks.isEmpty()) {
            return tracks;
        }
        final Map<String, List<LyricsLine>> out = new HashMap<>(2 * tracks.size());
        for (Map.Entry<String, List<LyricsLine>> entry : tracks.entrySet()) {
            out.put(entry.getKey(), widen(entry.getValue(), count, room));
        }
        return out;
    }
}
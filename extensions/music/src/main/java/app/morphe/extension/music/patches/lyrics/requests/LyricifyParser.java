/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.Word;

final class LyricifyParser {

    private static final Pattern LINE_TIMING =
            Pattern.compile("^\\[(\\d+),(\\d+)](.*)");

    private static final Pattern WORD_TIMING =
            Pattern.compile("([^(]+)\\((\\d+),(\\d+)\\)");

    private static final Pattern LRC_TIMESTAMP =
            Pattern.compile("\\[(\\d+):(\\d+[.]\\d+)](.*)");

    private LyricifyParser() {
    }

    static List<LyricsLine> parseLines(String text, int offsetMs) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        final List<LyricsLine> lines = new ArrayList<>();

        for (String raw : text.split("\\r?\\n")) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            final Matcher m = LINE_TIMING.matcher(line);
            if (!m.matches()) {
                continue;
            }

            final long startMs = Long.parseLong(Objects.requireNonNull(m.group(1))) + offsetMs;
            final long endMs = Long.parseLong(Objects.requireNonNull(m.group(2))) + offsetMs;
            final String body = Objects.requireNonNull(m.group(3)).trim();
            if (body.isEmpty()) {
                continue;
            }

            lines.add(new LyricsLine(Math.max(0, startMs), Math.max(0, endMs), body, List.of()));
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    static List<LyricsLine> parseSyllable(String text, int offsetMs) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        final List<LyricsLine> lines = new ArrayList<>();

        for (String raw : text.split("\\r?\\n")) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            final String body;
            if (line.startsWith("[")) {
                final int close = line.indexOf(']');
                body = (close > 0) ? line.substring(close + 1) : line;
            } else {
                body = line;
            }

            final List<Word> words = parseSyllableWords(body, offsetMs);
            if (words.isEmpty()) {
                continue;
            }

            final String fullText = joinWordTexts(words);
            final long startMs = words.get(0).startMs();
            final long endMs = words.get(words.size() - 1).endMs();
            lines.add(new LyricsLine(startMs, endMs, fullText, words));
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    private static List<Word> parseSyllableWords(String body, int offsetMs) {
        final List<Word> words = new ArrayList<>();
        final Matcher m = WORD_TIMING.matcher(body);

        while (m.find()) {
            final String wordText = Objects.requireNonNull(m.group(1));
            final long startMs = Long.parseLong(Objects.requireNonNull(m.group(2))) + offsetMs;
            final long durMs = Long.parseLong(Objects.requireNonNull(m.group(3)));
            final long endMs = startMs + durMs;

            final boolean endsWithSpace = wordText.endsWith(" ");
            words.add(new Word(
                    Math.max(0, startMs),
                    Math.max(0, endMs),
                    wordText,
                    null,
                    endsWithSpace));
        }

        return words;
    }

    private static String joinWordTexts(List<Word> words) {
        final StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            sb.append(w.text());
        }
        return sb.toString();
    }

    static List<String> parseTranslation(String trans, int offsetMs) {
        if (trans == null || trans.isEmpty()) {
            return Collections.emptyList();
        }

        final List<TranslatedEntry> entries = new ArrayList<>();

        for (String raw : trans.split("\\r?\\n")) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            final Matcher m = LRC_TIMESTAMP.matcher(line);
            if (!m.matches()) {
                continue;
            }

            final long min = Long.parseLong(Objects.requireNonNull(m.group(1)));
            final String secPart = Objects.requireNonNull(m.group(2));
            final String text = Objects.requireNonNull(m.group(3)).trim();
            if (text.isEmpty()) {
                continue;
            }

            final long timeMs = parseLrcSeconds(min, secPart) + offsetMs;
            entries.add(new TranslatedEntry(timeMs, text));
        }

        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        entries.sort(Comparator.comparingLong(TranslatedEntry::timeMs));

        final List<String> result = new ArrayList<>(entries.size());
        for (TranslatedEntry e : entries) {
            result.add(e.text());
        }
        return result;
    }

    private static long parseLrcSeconds(long minutes, String secPart) {
        final int dot = secPart.indexOf('.');
        final long seconds;
        final long fractionMs;
        if (dot < 0) {
            seconds = Long.parseLong(secPart);
            fractionMs = 0;
        } else {
            seconds = Long.parseLong(secPart.substring(0, dot));
            fractionMs = LyricsRequests.fractionToMs(secPart.substring(dot + 1));
        }
        return (minutes * 60 + seconds) * 1000 + fractionMs;
    }

    private record TranslatedEntry(long timeMs, String text) {}
}

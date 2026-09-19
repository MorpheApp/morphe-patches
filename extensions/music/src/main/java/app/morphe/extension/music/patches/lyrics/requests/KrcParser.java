/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.Word;

public final class KrcParser {

    private static final Pattern KRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)");
    private static final Pattern KRC_WORD = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)");

    private KrcParser() {
    }

    public static List<LyricsLine> parse(String krc) {
        List<LyricsLine> lines = new ArrayList<>();
        if (krc == null || krc.isEmpty()) {
            return lines;
        }

        for (String rawLine : krc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) != '[') {
                continue;
            }

            Matcher lineMatch = KRC_LINE.matcher(line);
            if (!lineMatch.matches()) {
                continue;
            }

            // Every group of both patterns is mandatory, so a match fills them all. The
            // guard keeps that from turning silent if a pattern ever gains an optional group.
            String startText = lineMatch.group(1);
            String durationText = lineMatch.group(2);
            String content = lineMatch.group(3);
            if (startText == null || durationText == null || content == null) {
                continue;
            }

            long lineStart = Long.parseLong(startText);
            long lineDuration = Long.parseLong(durationText);
            long lineEnd = lineStart + lineDuration;

            List<Long> offsets = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            Matcher wordMatch = KRC_WORD.matcher(content);
            while (wordMatch.find()) {
                String offsetText = wordMatch.group(1);
                String wordText = wordMatch.group(4);
                if (offsetText == null || wordText == null) {
                    continue;
                }
                offsets.add(Long.parseLong(offsetText));
                texts.add(wordText);
            }

            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < offsets.size(); i++) {
                long wordStart = lineStart + offsets.get(i);
                long wordEnd = (i < offsets.size() - 1) ? lineStart + offsets.get(i + 1) : lineEnd;
                String text = texts.get(i);
                words.add(new Word(wordStart, wordEnd, text));
                full.append(text);
            }
            if (words.isEmpty() && !content.isEmpty()) {
                words.add(new Word(lineStart, lineEnd, content));
                full.append(content);
            }

            String text = full.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            lines.add(new LyricsLine(lineStart, text, words));
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }
}

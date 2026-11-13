/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

/**
 * Turns a line's words into the timings the karaoke pass lights, matching each word back to the
 * text that is drawn. Works on plain lyrics models, so it stays free of any view or span.
 */
final class LyricsWordTimingCalc {

    private LyricsWordTimingCalc() {
    }

    static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index,
                                               boolean splitWordlessLine) {
        LyricsLine line = lines.get(index);
        String lineText = LyricsTextUtils.displayLineText(line);
        // A lone word with furigana stays whole, so its reading sits over all of it. Any other
        // lone word is swept by character across its own span, but only when it has one: a
        // line timed only as a whole lights in one go like every other such line in the song.
        if (!LyricsReadingCalc.hasAnyRuby(line) && LyricsWordBoundaries.countWords(lineText) == 1 && line.words().size() == 1
                && line.words().get(0).endMs() > line.words().get(0).startMs()) {
            Word singleWord = line.words().get(0);
            long rangeStart = singleWord.startMs() >= 0 ? singleWord.startMs() : line.startTimeMs();
            long rangeEnd = singleWord.endMs() > rangeStart ? singleWord.endMs() : line.endTimeMs();
            if (rangeEnd <= rangeStart) {
                rangeEnd = index + 1 < lines.size()
                        ? lines.get(index + 1).startTimeMs()
                        : LyricsLine.NO_TIME;
            }
            if (rangeStart >= 0 && rangeEnd > rangeStart) {
                List<WordTiming> synthesized =
                        LyricsWordBoundaries.synthesizeLineChars(lineText, rangeStart, rangeEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        } else if (splitWordlessLine && !line.hasWords()) {
            // An only or dual mode shows the translation or the reading in its main slot while
            // the timings it lights by are still the original's, so a line carrying no word
            // timings leaves that slot with nothing to light. Splitting it is what fills it.
            final long lineStart = line.startTimeMs();
            final long lineEnd = line.endTimeMs() > lineStart ? line.endTimeMs()
                    : index + 1 < lines.size() ? lines.get(index + 1).startTimeMs()
                      : LyricsLine.NO_TIME;
            if (lineStart >= 0 && lineEnd > lineStart) {
                List<WordTiming> synthesized =
                        LyricsWordBoundaries.synthesizeLineWords(lineText, lineStart, lineEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            } else if (lineStart <= 0) {
                // Plain text has no timing; a dummy range lets the per-syllable
                // romaji align under each character.
                List<WordTiming> synthesized = LyricsWordBoundaries.synthesizeLineWords(lineText, 0, 1);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        }
        return computeWordTimingsFromWords(line);
    }

    /** The line's own word timings, with any text they leave out lit along with its neighbours. */
    static List<WordTiming> computeWordTimingsFromWords(LyricsLine line) {
        final boolean hasRealTimings = line.hasWordTimings();
        if (!hasRealTimings && !LyricsReadingCalc.hasAnyRuby(line)) {
            return Collections.emptyList();
        }

        List<WordTiming> timings = new ArrayList<>(line.words().size() + 2);
        String text = LyricsTextUtils.displayLineText(line);
        final int textLength = text.length();
        if (textLength == 0) {
            return Collections.emptyList();
        }

        // The drawn text of a background vocal has lost its brackets, so its words lose them too.
        final boolean secondary = LyricsTextUtils.isSecondaryLine(line);
        // Ruby on a plain-text line has no real timing; all its timings are averaged.
        final boolean averaged = !line.hasWordTimings();
        int offset = 0;
        for (Word word : line.words()) {
            String rawWordText = secondary
                    ? LyricsBracketSplitter.stripAllBrackets(word.text()) : word.text();
            String trimmed = rawWordText.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final boolean wordTrailingSpace = word.endsWithSpace() || rawWordText.endsWith(" ");
            String rawRom = word.romaji();
            if (rawRom != null && secondary) {
                rawRom = LyricsBracketSplitter.stripAllBrackets(rawRom);
            }

            final long wStart = hasRealTimings ? word.startMs() : LyricsLine.NO_TIME;
            final long wEnd = hasRealTimings ? word.endMs() : LyricsLine.NO_TIME;

            // A timing covering several words is shared out between them by length.
            if (trimmed.indexOf(' ') >= 0) {
                String[] parts = trimmed.split("\\s+");
                String[] romParts = (rawRom != null && !rawRom.trim().isEmpty())
                        ? rawRom.trim().split("\\s+") : null;
                int totalChars = 0;
                for (String p : parts) {
                    totalChars += p.length();
                }
                totalChars = Math.max(1, totalChars);
                final long totalDur = Math.max(1L, wEnd - wStart);
                long curStartMs = wStart;

                for (int pi = 0; pi < parts.length; pi++) {
                    String part = parts[pi];
                    if (part.isEmpty()) {
                        continue;
                    }
                    final boolean isLast = (pi == parts.length - 1);
                    final long partDur = (totalDur * part.length()) / totalChars;
                    final long partEndMs = !hasRealTimings ? LyricsLine.NO_TIME
                            : (isLast ? Math.max(curStartMs + 1L, wEnd) : curStartMs + Math.max(1L, partDur));
                    String partRom = (romParts != null && romParts.length == parts.length)
                            ? romParts[pi] : null;
                    offset = appendMatchedWordTiming(
                            timings, text, textLength, offset, part, wordToken(part),
                            hasRealTimings ? curStartMs : LyricsLine.NO_TIME, partEndMs,
                            partRom, !isLast || wordTrailingSpace, word.ruby(), averaged);
                    curStartMs = partEndMs;
                }
            } else {
                offset = appendMatchedWordTiming(
                        timings, text, textLength, offset, trimmed, wordToken(trimmed),
                        wStart, wEnd, rawRom, wordTrailingSpace, word.ruby(), averaged);
            }
        }

        // Text after the last timed word, such as closing punctuation, is lit after it.
        if (hasRealTimings && offset < textLength && !timings.isEmpty()) {
            fillUntimedGap(timings, text, textLength, offset, textLength,
                    timings.get(timings.size() - 1).endMs(),
                    Math.max(line.endTimeMs(), timings.get(timings.size() - 1).endMs() + 200L));
        }

        // Word timings from source are kept with their original durations without clamping
        // so overlapping vocals / held notes do not cut short prematurely.
        return timings;
    }

    static int findToken(String text, int offset, String token) {
        int start = text.indexOf(token, offset);
        return start < 0 ? LyricsTextUtils.indexOfIgnoreCase(text, token, offset) : start;
    }

    /** A source may wrap a word the line draws bare. */
    static String wordToken(String word) {
        int start = 0;
        int end = word.length();
        while (start < end && isSurroundingMark(word.charAt(start))) {
            start++;
        }
        while (end > start && isSurroundingMark(word.charAt(end - 1))) {
            end--;
        }
        return start == end ? word : word.substring(start, end);
    }

    static boolean isSurroundingMark(char c) {
        return Character.isWhitespace(c)
                || c == '"' || c == '\'' || c == '“' || c == '”' || c == '‘' || c == '’'
                || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '（' || c == '）' || c == '【' || c == '】';
    }

    /** {@return wordStart, span end, word end} */
    static int[] wordHit(String text, int textLength, int start, String token) {
        final int spanEnd = Math.min(start + token.length(), textLength);
        int wordStart = start;
        while (wordStart < spanEnd && isSurroundingMark(text.charAt(wordStart))) {
            wordStart++;
        }
        int wordEnd = spanEnd;
        while (wordEnd > wordStart && isSurroundingMark(text.charAt(wordEnd - 1))) {
            wordEnd--;
        }
        return new int[]{wordStart, spanEnd, wordEnd};
    }

    /** {@return the hit, or -1 when it is not there or only within a longer word} */
    static int usableHit(String text, int textLength, int offset, String token,
                         boolean wordTrailingSpace) {
        int firstStart = findToken(text, offset, token);
        if (firstStart < 0) {
            return -1;
        }
        int nextCharOffset = Math.max(0, offset);
        while (nextCharOffset < textLength && isSurroundingMark(text.charAt(nextCharOffset))) {
            nextCharOffset++;
        }
        if (firstStart <= nextCharOffset) {
            final int[] hit = wordHit(text, textLength, firstStart, token);
            final int wordEnd = hit[2];
            final boolean nextCont = wordEnd < textLength && LyricsWordBoundaries.isWordContinuation(text.charAt(wordEnd));
            if (!wordTrailingSpace || !nextCont) {
                return firstStart;
            }
        }
        int searchFrom = offset;
        int firstWordStartCandidate = -1;
        while (searchFrom < textLength) {
            int start = findToken(text, searchFrom, token);
            if (start < 0) {
                break;
            }
            final int[] hit = wordHit(text, textLength, start, token);
            final int wordStart = hit[0];
            final int wordEnd = hit[2];
            if (wordEnd <= wordStart) {
                return start;
            }
            final boolean prevCont = wordStart > 0 && LyricsWordBoundaries.isWordContinuation(text.charAt(wordStart - 1));
            final boolean nextCont = wordEnd < textLength && LyricsWordBoundaries.isWordContinuation(text.charAt(wordEnd));
            if (!prevCont && !nextCont) {
                return start;
            }
            if (!prevCont && firstWordStartCandidate < 0) {
                firstWordStartCandidate = start;
            }
            searchFrom = start + 1;
        }
        if (!wordTrailingSpace && firstWordStartCandidate >= 0) {
            return firstWordStartCandidate;
        }
        return -1;
    }

    static int appendMatchedWordTiming(
            List<WordTiming> timings,
            String text,
            int textLength,
            int offset,
            String token,
            String bareToken,
            long startMs,
            long endMs,
            @Nullable String rom,
            boolean wordTrailingSpace,
            @Nullable String ruby,
            boolean averaged) {
        // The drawn line may hold the word's marks elsewhere, hence the bare word to fall back on.
        int start = usableHit(text, textLength, offset, token, wordTrailingSpace);
        String matched = token;
        if (start < 0 && !token.equals(bareToken)) {
            start = usableHit(text, textLength, offset, bareToken, wordTrailingSpace);
            matched = bareToken;
        }
        if (start < 0) {
            // Left where it was, so words after an unmatched one can still be found.
            return offset;
        }
        final int[] hit = wordHit(text, textLength, start, matched);
        final int wordStart = hit[0];
        final int spanEnd = hit[1];
        final int wordEnd = hit[2];
        final boolean hasTiming = startMs != LyricsLine.NO_TIME && endMs != LyricsLine.NO_TIME;
        final long timedStart = hasTiming ? startMs : LyricsLine.NO_TIME;
        final long timedEnd = hasTiming ? Math.max(endMs, startMs + 1L) : LyricsLine.NO_TIME;
        if (hasTiming && start > offset) {
            final long gapStartMs = timings.isEmpty()
                    ? Math.max(0L, timedStart - 150L)
                    : timings.get(timings.size() - 1).endMs();
            fillUntimedGap(timings, text, textLength, offset, start, gapStartMs, timedStart);
        }
        if (spanEnd <= start) {
            return offset;
        }
        if (hasTiming && start < wordStart) {
            appendMarksTiming(timings, text, textLength, start, wordStart, timedStart, averaged);
        }
        if (wordEnd > wordStart) {
            final boolean trailSpace = wordTrailingSpace
                    || (wordEnd < textLength && Character.isWhitespace(text.charAt(wordEnd)));
            timings.add(new WordTiming(wordStart, wordEnd, timedStart, timedEnd, rom, trailSpace, ruby, averaged));
        } else if (hasTiming) {
            appendMarksTiming(timings, text, textLength, wordStart, spanEnd, timedStart, averaged);
        }
        if (hasTiming && wordEnd < spanEnd) {
            appendMarksTiming(timings, text, textLength, wordEnd, spanEnd, timedEnd, averaged);
        }
        return spanEnd;
    }

    /** A share of the word's own time would put a reading on syllable marks it never held. */
    static void appendMarksTiming(List<WordTiming> timings, String text, int textLength,
                                  int start, int end, long atMs, boolean averaged) {
        if (start >= end) {
            return;
        }
        final boolean trailSpace = end < textLength && Character.isWhitespace(text.charAt(end));
        timings.add(new WordTiming(start, end, atMs, atMs + 1L, null, trailSpace, null, averaged));
    }

    static void fillUntimedGap(
            List<WordTiming> timings,
            String text,
            int textLength,
            int gapStart,
            int gapEnd,
            long startMs,
            long endMs) {
        String gap = text.substring(gapStart, gapEnd);
        if (gap.trim().isEmpty()) {
            if (!timings.isEmpty() && gap.indexOf(' ') >= 0) {
                final int lastIdx = timings.size() - 1;
                WordTiming prev = timings.get(lastIdx);
                if (!prev.hasTrailingSpace()) {
                    timings.set(lastIdx, new WordTiming(
                            prev.start(), prev.end(), prev.startMs(), prev.endMs(), prev.romaji(),
                            true, prev.ruby(), prev.averaged()));
                }
            }
            return;
        }

        int[] gapBounds = LyricsWordBoundaries.wordBoundaries(gap);
        List<int[]> spans = new ArrayList<>(2);
        for (int i = 0; i + 1 < gapBounds.length; i++) {
            if (LyricsWordBoundaries.isWord(gap, gapBounds[i], gapBounds[i + 1])) {
                spans.add(new int[]{gapStart + gapBounds[i], gapStart + gapBounds[i + 1]});
            }
        }
        if (spans.isEmpty()) {
            return;
        }

        final long safeStart = Math.max(0L, startMs);
        final long safeEnd = Math.max(safeStart + spans.size(), endMs);
        final long dur = safeEnd - safeStart;
        for (int idx = 0; idx < spans.size(); idx++) {
            int[] sp = spans.get(idx);
            String tok = text.substring(sp[0], sp[1]);
            if (!timings.isEmpty() && LyricsWordBoundaries.isPurePunctuation(tok)) {
                final int lastIdx = timings.size() - 1;
                WordTiming prev = timings.get(lastIdx);
                if (prev.end() == sp[0]) {
                    final boolean trail = sp[1] < textLength && Character.isWhitespace(text.charAt(sp[1]));
                    timings.set(lastIdx, new WordTiming(
                            prev.start(), sp[1], prev.startMs(), prev.endMs(), prev.romaji(), trail, prev.ruby(), prev.averaged()));
                    continue;
                }
            }
            final long wStart = safeStart + (dur * idx) / spans.size();
            final long wEnd = safeStart + (dur * (idx + 1)) / spans.size();
            final boolean trail = sp[1] < textLength && Character.isWhitespace(text.charAt(sp[1]));
            timings.add(new WordTiming(sp[0], sp[1], wStart, Math.max(wEnd, wStart + 1L), null, trail, true));
        }
    }
}

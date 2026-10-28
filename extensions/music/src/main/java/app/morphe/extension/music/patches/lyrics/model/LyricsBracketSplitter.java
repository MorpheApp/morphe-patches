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

import app.morphe.extension.music.patches.lyrics.TextTrim;

public final class LyricsBracketSplitter {

    private static final LyricsLine BLANK = new LyricsLine(LyricsLine.NO_TIME, "");

    private static final Pattern NOT_SUNG_RUN = Pattern.compile(
            "(?i)^[\\s()（）\\[\\]]*(?:x\\s*\\d+|\\d+\\s*x|repeat(?:\\s*x?\\d+)?|chorus|verse(?:\\s*\\d+)?|pre[-\\s]*chorus|post[-\\s]*chorus|intro|outro|instrumental|bridge|hook|interlude|solo|music|guitar\\s*solo)[\\s()（）\\[\\]]*$");

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

        List<LyricsLine> lines = lyrics.lines();
        final int count = lines.size();
        Cut[] cuts = new Cut[count];
        LyricsLine[] normalizedBg = new LyricsLine[count];
        int[] room = new int[count];
        int extra = 0;
        boolean anyNormalized = false;

        for (int i = 0; i < count; i++) {
            Cut c = cut(lines, i);
            if (c != null) {
                cuts[i] = c;
                room[i] = 2;
                extra++;
            } else {
                room[i] = 1;
                LyricsLine norm = normalizeStandaloneBg(lines, i);
                if (norm != null) {
                    normalizedBg[i] = norm;
                    anyNormalized = true;
                }
            }
        }

        if (extra == 0 && !anyNormalized) {
            return lyrics;
        }

        List<LyricsLine> split = new ArrayList<>(count + extra);
        for (int i = 0; i < count; i++) {
            Cut c = cuts[i];
            if (c != null) {
                split.add(c.sung());
                split.add(c.background());
            } else if (normalizedBg[i] != null) {
                split.add(normalizedBg[i]);
            } else {
                split.add(lines.get(i));
            }
        }

        List<LyricsLine> finalLines = split;
        if (lyrics.synced() && !finalLines.isEmpty()) {
            finalLines = Lyrics.clampLastWordEnds(Lyrics.fixAnomalousWordTimestamps(finalLines));
        }

        return new Lyrics(
                finalLines,
                lyrics.providerName(),
                lyrics.synced(),
                widen(lyrics.romanization(), count, room, cuts, normalizedBg, true),
                widenMap(lyrics.translations(), count, room, cuts, normalizedBg, false),
                widenMap(lyrics.romanizations(), count, room, cuts, normalizedBg, true),
                lyrics.songwriters(),
                lyrics.rawFormat(),
                lyrics.formatType(),
                lyrics.sourceUrl(),
                lyrics.about());
    }

    /**
     * Splits a line sung partly in round brackets, half or full width, into the lead outside
     * them and the background vocal inside.
     */
    @Nullable
    private static Cut cut(List<LyricsLine> lines, int index) {
        LyricsLine line = lines.get(index);
        String text = line.text();
        // A background line stays one: cutting it would turn the part outside the brackets into a lead.
        if (line.isBG() || text.trim().isEmpty() || hasTagBrackets(text)) {
            return null;
        }

        List<Run> runs = findSungBracketRuns(text);
        if (runs.isEmpty()) {
            return null;
        }

        String outsideText = extractOutsideText(text, runs);
        String insideText = extractInsideText(text, runs);
        if (!sung(outsideText) || !sung(insideText)) {
            return null;
        }

        if (line.hasWords()) {
            Cut wordCut = cutWordTimed(line, runs, outsideText, insideText);
            if (wordCut != null) {
                return wordCut;
            }
        }
        return cutLineTimed(line, runs, outsideText, insideText);
    }

    /**
     * A line written whole in brackets, such as "(Ooh, yeah)", becomes a background line of the lead
     * before it, and a background line loses the brackets it still carries in its text or words.
     */
    @Nullable
    private static LyricsLine normalizeStandaloneBg(List<LyricsLine> lines, int index) {
        LyricsLine line = lines.get(index);
        String raw = line.text();
        if (raw.trim().isEmpty() || hasTagBrackets(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        final boolean outerParens = hasBalancedOuterBrackets(trimmed);
        if (!line.isBG() && (!outerParens || index == 0)) {
            return null;
        }
        String strippedText = stripOuterBrackets(trimmed);
        if (!sung(strippedText)) {
            return null;
        }
        // A bracketed line with no lead above it is the lead itself.
        if (!line.isBG()) {
            boolean hasLeadBefore = false;
            for (int p = index - 1; p >= 0; p--) {
                LyricsLine prev = lines.get(p);
                if (!prev.isBG() && !hasBalancedOuterBrackets(prev.text().trim())) {
                    hasLeadBefore = true;
                    break;
                }
            }
            if (!hasLeadBefore) {
                return null;
            }
        }

        List<Word> origWords = line.words();
        List<Word> cleanedWords = origWords.isEmpty() ? origWords : trimBrackets(origWords);
        final boolean textChanged = !strippedText.equals(raw);
        final boolean bgChanged = !line.isBG();
        boolean wordsChanged = cleanedWords.size() != origWords.size();
        if (!wordsChanged) {
            for (int i = 0; i < cleanedWords.size(); i++) {
                if (!cleanedWords.get(i).text().equals(origWords.get(i).text())) {
                    wordsChanged = true;
                    break;
                }
            }
        }
        if (!textChanged && !bgChanged && !wordsChanged) {
            return null;
        }
        return new LyricsLine(
                line.startTimeMs(),
                line.endTimeMs(),
                strippedText,
                cleanedWords,
                line.agentId(),
                line.isDuet(),
                true,
                line.songPart());
    }

    @Nullable
    private static Cut cutWordTimed(LyricsLine line, List<Run> runs,
                                    String fallbackSungText, String fallbackBgText) {
        String text = line.text();
        List<Word> words = line.words();
        if (words.isEmpty()) {
            return null;
        }

        List<Word> sung = new ArrayList<>(words.size());
        List<Word> background = new ArrayList<>(words.size());
        int searchFrom = 0;

        for (int wi = 0; wi < words.size(); wi++) {
            Word word = words.get(wi);
            String wText = word.text();
            if (wText == null || wText.isEmpty()) {
                continue;
            }

            while (searchFrom < text.length() && Character.isWhitespace(text.charAt(searchFrom))) {
                searchFrom++;
            }

            int found = text.indexOf(wText, searchFrom);
            int matchLen = wText.length();
            if (found < 0) {
                String trimmedW = wText.trim();
                if (!trimmedW.isEmpty()) {
                    found = text.indexOf(trimmedW, searchFrom);
                    matchLen = trimmedW.length();
                }
            }

            final int from;
            final int to;
            if (found >= 0) {
                from = found;
                to = Math.min(text.length(), found + matchLen);
                searchFrom = to;
            } else {
                from = Math.min(searchFrom, text.length());
                to = Math.min(text.length(), from + wText.trim().length());
                searchFrom = to;
            }

            final boolean spaceAfterInText = to < text.length() && Character.isWhitespace(text.charAt(to));
            final boolean hasSpace = word.endsWithSpace() || spaceAfterInText;

            int inBackground = 0;
            for (int ri = 0; ri < runs.size(); ri++) {
                Run r = runs.get(ri);
                inBackground += Math.max(0, Math.min(to, r.close() + 1) - Math.max(from, r.open()));
            }
            final int spanLen = Math.max(0, to - from);
            final int inSung = Math.max(0, spanLen - inBackground);

            if (inSung <= 0 && inBackground <= 0) {
                // Fallback: if the word itself starts and ends with brackets, it belongs to background.
                String wt = wText.trim();
                if (!wt.isEmpty() && (isOpen(wt.charAt(0)) || isClose(wt.charAt(wt.length() - 1)))) {
                    background.add(new Word(word.startMs(), word.endMs(), wText, word.romaji(), hasSpace));
                } else {
                    sung.add(new Word(word.startMs(), word.endMs(), wText, word.romaji(), hasSpace));
                }
                continue;
            }

            if (inBackground == 0) {
                sung.add(new Word(word.startMs(), word.endMs(), wText, word.romaji(), hasSpace));
            } else if (inSung == 0) {
                background.add(new Word(word.startMs(), word.endMs(), wText, word.romaji(), hasSpace));
            } else {
                // Single Word token straddles a bracket boundary (e.g. "you(yeah)" or "(ooh)baby").
                StringBuilder sungPart = new StringBuilder();
                StringBuilder bgPart = new StringBuilder();
                boolean startsInBg = false;
                for (int ci = from; ci < to && ci < text.length(); ci++) {
                    final boolean charInBg = isIndexInsideRuns(ci, runs);
                    if (ci == from) {
                        startsInBg = charInBg;
                    }
                    if (charInBg) {
                        bgPart.append(text.charAt(ci));
                    } else {
                        sungPart.append(text.charAt(ci));
                    }
                }
                String sStr = stripAllBrackets(sungPart.toString()).trim();
                String bStr = stripAllBrackets(bgPart.toString()).trim();
                final long wStart = word.startMs();
                final long wEnd = word.endMs();
                final long dur = (wStart != LyricsLine.NO_TIME && wEnd > wStart) ? (wEnd - wStart) : 0L;
                final long splitTime = (dur > 0 && spanLen > 0)
                        ? wStart + (dur * (startsInBg ? inBackground : inSung)) / spanLen
                        : wStart;

                if (!sStr.isEmpty()) {
                    final long sStart = startsInBg ? splitTime : wStart;
                    final long sEnd = startsInBg ? wEnd : splitTime;
                    sung.add(new Word(sStart, sEnd, sStr, word.romaji(), !startsInBg || hasSpace));
                }
                if (!bStr.isEmpty()) {
                    final long bStart = startsInBg ? wStart : splitTime;
                    final long bEnd = startsInBg ? splitTime : wEnd;
                    background.add(new Word(bStart, bEnd, bStr, null, startsInBg || hasSpace));
                }
            }
        }

        if (sung.isEmpty() || background.isEmpty()) {
            return null;
        }

        List<Word> trimmedSung = trimBrackets(sung);
        List<Word> trimmedBackground = trimBrackets(background);
        if (trimmedSung.isEmpty() || trimmedBackground.isEmpty()) {
            return null;
        }

        String sungText = textOf(trimmedSung);
        String backgroundText = textOf(trimmedBackground);
        if (sungText.trim().isEmpty()) {
            sungText = fallbackSungText;
        }
        if (backgroundText.trim().isEmpty()) {
            backgroundText = fallbackBgText;
        }
        if (!sung(sungText) || !sung(backgroundText)) {
            return null;
        }

        final long bgStart = firstValidStart(trimmedBackground, line.startTimeMs());
        final long bgEnd = lastValidEnd(trimmedBackground, line.endTimeMs());
        final long sungWordStart = firstValidStart(trimmedSung, line.startTimeMs());
        final long sungWordEnd = lastValidEnd(trimmedSung, line.endTimeMs());

        // Parent sungLine's line-level [startTimeMs, endTimeMs] spans the full cluster so
        // Lyrics.indexForPosition activates the parent line even when bgBeforeMain starts early,
        // while sungLine.words() and bgLine.words() keep their exact individual word timings.
        long parentStart = sungWordStart;
        if (line.startTimeMs() != LyricsLine.NO_TIME && parentStart != LyricsLine.NO_TIME) {
            parentStart = Math.min(line.startTimeMs(), parentStart);
        }
        if (bgStart != LyricsLine.NO_TIME && parentStart != LyricsLine.NO_TIME) {
            parentStart = Math.min(parentStart, bgStart);
        }
        long parentEnd = sungWordEnd;
        if (line.endTimeMs() != LyricsLine.NO_TIME && parentEnd != LyricsLine.NO_TIME) {
            parentEnd = Math.max(line.endTimeMs(), parentEnd);
        }
        if (bgEnd != LyricsLine.NO_TIME && parentEnd != LyricsLine.NO_TIME) {
            parentEnd = Math.max(parentEnd, bgEnd);
        }

        LyricsLine sungLine = new LyricsLine(
                parentStart, parentEnd, sungText, trimmedSung,
                line.agentId(), line.isDuet(), false, line.songPart());
        LyricsLine bgLine = new LyricsLine(
                bgStart, bgEnd, backgroundText, trimmedBackground,
                line.agentId(), line.isDuet(), true, line.songPart());
        return new Cut(sungLine, bgLine);
    }

    @Nullable
    private static Cut cutLineTimed(LyricsLine line, List<Run> runs,
                                    String outsideText, String insideText) {
        String text = line.text();
        int firstNonSpace = 0;
        while (firstNonSpace < text.length() && Character.isWhitespace(text.charAt(firstNonSpace))) {
            firstNonSpace++;
        }
        final boolean headBeforeMain = runs.size() == 1 && runs.get(0).open() == firstNonSpace;
        return finishText(line, outsideText, insideText, headBeforeMain);
    }

    @Nullable
    private static Cut finishText(LyricsLine line, String sungText, String backgroundText,
                                  boolean headBeforeMain) {
        String kept = stripAllBrackets(sungText).trim();
        String bgKept = stripAllBrackets(backgroundText).trim();
        if (kept.isEmpty() || bgKept.isEmpty() || !sung(kept) || !sung(bgKept)) {
            return null;
        }
        LyricsLine bgLine = headBeforeMain && line.startTimeMs() > 0
                ? new LyricsLine(line.startTimeMs() - 1L, line.endTimeMs(),
                        bgKept, List.of(), line.agentId(),
                        line.isDuet(), true, line.songPart())
                : plain(line, bgKept, List.of(), true);
        return new Cut(plain(line, kept, List.of(), false), bgLine);
    }

    private static LyricsLine plain(LyricsLine line, String text, List<Word> words, boolean isBG) {
        return new LyricsLine(line.startTimeMs(), line.endTimeMs(), text, words,
                line.agentId(), line.isDuet(), isBG, line.songPart());
    }

    private static long firstValidStart(List<Word> words, long fallback) {
        for (int i = 0; i < words.size(); i++) {
            final long s = words.get(i).startMs();
            if (s != LyricsLine.NO_TIME && s >= 0) {
                return s;
            }
        }
        return fallback;
    }

    private static long lastValidEnd(List<Word> words, long fallback) {
        for (int i = words.size() - 1; i >= 0; i--) {
            final long e = words.get(i).endMs();
            if (e != LyricsLine.NO_TIME && e > 0) {
                return e;
            }
        }
        return fallback;
    }

    /**
     * Strips leading/trailing round brackets from every word while preserving {@code endsWithSpace()}.
     */
    private static List<Word> trimBrackets(List<Word> words) {
        List<Word> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            String body = word.text();
            if (body == null) {
                continue;
            }
            final boolean hadTrailingWhitespace = !body.isEmpty()
                    && Character.isWhitespace(body.charAt(body.length() - 1));
            body = TextTrim.stripLeading(body);
            while (!body.isEmpty() && isOpen(body.charAt(0))) {
                body = TextTrim.stripLeading(body.substring(1));
            }
            body = TextTrim.stripTrailing(body);
            while (!body.isEmpty() && isClose(body.charAt(body.length() - 1))) {
                body = TextTrim.stripTrailing(body.substring(0, body.length() - 1));
            }
            // Also strip any stray internal round brackets left in a token.
            body = stripAllBrackets(body).trim();
            if (body.isEmpty()) {
                continue;
            }
            String roma = word.romaji();
            if (roma != null && !roma.isEmpty()) {
                roma = stripAllBrackets(roma).trim();
                if (roma.isEmpty()) {
                    roma = null;
                }
            }
            final boolean endsWithSpace = word.endsWithSpace() || hadTrailingWhitespace;
            out.add(new Word(word.startMs(), word.endMs(), body, roma, endsWithSpace));
        }
        if (!out.isEmpty()) {
            final int lastIdx = out.size() - 1;
            Word last = out.get(lastIdx);
            if (last.endsWithSpace()) {
                out.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text(), last.romaji(), false));
            }
        }
        return out;
    }

    private static List<Run> findSungBracketRuns(String text) {
        List<Run> runs = new ArrayList<>(2);
        int i = 0;
        final int len = text.length();
        while (i < len) {
            final char c = text.charAt(i);
            if (isOpen(c)) {
                Run run = match(text, i, len, true);
                if (run != null) {
                    String inner = text.substring(run.open() + 1, run.close());
                    if (sung(inner)) {
                        runs.add(run);
                        i = run.close() + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return runs;
    }

    private static boolean isIndexInsideRuns(int charIndex, List<Run> runs) {
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (charIndex >= r.open() && charIndex <= r.close()) {
                return true;
            }
        }
        return false;
    }

    private static String extractOutsideText(String text, List<Run> runs) {
        if (runs.isEmpty()) {
            return text.trim();
        }
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (r.open() > last) {
                sb.append(text, last, r.open());
            }
            last = r.close() + 1;
        }
        if (last < text.length()) {
            sb.append(text, last, text.length());
        }
        return sb.toString()
                .replaceAll("\\s+([!?,.:;])", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String extractInsideText(String text, List<Run> runs) {
        if (runs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            String part = stripAllBrackets(text.substring(r.open() + 1, r.close())).trim();
            if (!part.isEmpty()) {
                //noinspection SizeReplaceableByIsEmpty
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /** Lenticular brackets hold a title or credit tag, never a vocal. */
    public static boolean hasTagBrackets(String text) {
        return text.indexOf('【') >= 0 || text.indexOf('】') >= 0;
    }

    /** A whole line in round brackets, which is how a background vocal is usually written. */
    public static boolean isBracketedVocal(@Nullable String text) {
        return text != null && !hasTagBrackets(text) && hasBalancedOuterBrackets(text);
    }

    public static boolean hasBalancedOuterBrackets(@Nullable String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2) {
            return false;
        }
        final char first = trimmed.charAt(0);
        if (!isOpen(first)) {
            return false;
        }
        Run matched = match(trimmed, 0, trimmed.length(), true);
        return matched != null && matched.close() == trimmed.length() - 1;
    }

    public static String stripOuterBrackets(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String trimmed = text.trim();
        while (trimmed.length() >= 2 && hasBalancedOuterBrackets(trimmed)) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    public static String stripAllBrackets(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (!isOpen(c) && !isClose(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Nesting is counted so an inner bracket does not close the run early; "(oh]" is no run at all. */
    @Nullable
    private static Run match(String text, int from, int toward, boolean forward) {
        final char first = text.charAt(from);
        final char opener = forward ? first : openerOf(first);
        final char closer = forward ? closerOf(first) : first;
        if (opener == 0 || closer == 0) {
            return null;
        }
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
        return switch (open) {
            case '(' -> ')';
            case '（' -> '）';
            default -> 0;
        };
    }

    private static char openerOf(char close) {
        return switch (close) {
            case ')' -> '(';
            case '）' -> '（';
            default -> 0;
        };
    }

    /** A repeat count or section name is bracketed too, but it is not sung. */
    private static boolean sung(String text) {
        if (text == null) {
            return false;
        }
        String body = text.trim();
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

    private static boolean isContinuousCjk(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        final int cp = s.codePointBefore(s.length());
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA;
    }

    private static String textOf(List<Word> words) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            String wText = word.text();
            if (wText == null || wText.isEmpty()) {
                continue;
            }
            text.append(wText);
            if (i < words.size() - 1 && !wText.endsWith(" ")) {
                String nextText = words.get(i + 1).text();
                final boolean cjkPair = isContinuousCjk(wText) && isContinuousCjk(nextText);
                if (word.endsWithSpace() || !cjkPair) {
                    text.append(' ');
                }
            }
        }
        return text.toString().trim();
    }

    @Nullable
    private static String synthesizeRomajiFromWords(List<Word> words) {
        if (words == null || words.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            String r = w.romaji();
            if (r != null && !r.trim().isEmpty()) {
                any = true;
                sb.append(r.trim());
                if (i < words.size() - 1 && !r.endsWith(" ")) {
                    sb.append(' ');
                }
            }
        }
        return any ? sb.toString().trim() : null;
    }

    /** A track written against other lines stays as it is: shorter than the lyrics is the mismatch
 *  the readers already check for before trusting one. */
    @Nullable
    private static List<LyricsLine> widen(
            @Nullable List<LyricsLine> aligned,
            int count,
            int[] room,
            Cut[] cuts,
            LyricsLine[] normalizedBg,
            boolean isRomanization) {
        if (aligned == null || aligned.isEmpty() || aligned.size() != count) {
            return aligned;
        }
        List<LyricsLine> out = new ArrayList<>(count + 8);
        for (int i = 0; i < count; i++) {
            LyricsLine item = aligned.get(i);
            Cut mainCut = cuts[i];
            if (room[i] == 2 && mainCut != null) {
                String itemText = item != null && item.text() != null ? item.text() : "";
                List<Run> itemRuns = findSungBracketRuns(itemText);
                if (!itemRuns.isEmpty()) {
                    String outPart = extractOutsideText(itemText, itemRuns);
                    String inPart = extractInsideText(itemText, itemRuns);
                    if (!outPart.isEmpty() && !inPart.isEmpty()) {
                        out.add(new LyricsLine(item.startTimeMs(), outPart));
                        out.add(new LyricsLine(item.startTimeMs(), inPart));
                        continue;
                    }
                }
                if (isRomanization) {
                    String sungRoma = synthesizeRomajiFromWords(mainCut.sung().words());
                    String bgRoma = synthesizeRomajiFromWords(mainCut.background().words());
                    if (sungRoma != null || bgRoma != null) {
                        out.add(new LyricsLine(LyricsLine.NO_TIME,
                                sungRoma != null ? sungRoma : stripAllBrackets(itemText).trim()));
                        out.add(new LyricsLine(LyricsLine.NO_TIME,
                                bgRoma != null ? bgRoma : ""));
                        continue;
                    }
                }
                out.add(item);
                out.add(BLANK);
            } else if (normalizedBg[i] != null && item != null && item.text() != null) {
                String cleaned = stripOuterBrackets(item.text());
                out.add(cleaned.equals(item.text())
                        ? item
                        : new LyricsLine(item.startTimeMs(), item.endTimeMs(), cleaned,
                                item.words() != null ? trimBrackets(item.words()) : List.of(),
                                item.agentId(), item.isDuet(), true, item.songPart()));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    @Nullable
    private static Map<String, List<LyricsLine>> widenMap(
            @Nullable Map<String, List<LyricsLine>> tracks,
            int count,
            int[] room,
            Cut[] cuts,
            LyricsLine[] normalizedBg,
            boolean isRomanization) {
        if (tracks == null || tracks.isEmpty()) {
            return tracks;
        }
        Map<String, List<LyricsLine>> out = new HashMap<>(2 * tracks.size());
        for (Map.Entry<String, List<LyricsLine>> entry : tracks.entrySet()) {
            out.put(entry.getKey(),
                    widen(entry.getValue(), count, room, cuts, normalizedBg, isRomanization));
        }
        return out;
    }
}

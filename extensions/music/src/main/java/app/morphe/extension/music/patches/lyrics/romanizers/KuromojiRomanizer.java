/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.KuromojiTokenizer.Token;
import app.morphe.extension.music.patches.lyrics.romanizers.RubyGenerator.RubyStyle;

/**
 * Morphological Japanese romanizer and ruby generator powered by {@link KuromojiTokenizer}.
 *
 * <p>Provides:
 * <ul>
 *   <li>Context-aware full-line Hepburn romanization (particle readings {@code は->wa},
 *       {@code へ->e}, {@code を->o}; interjections {@code こんにちは->konnichiwa},
 *       {@code こんばんは->konbanwa}; cross-token sokuon gemination {@code っ/ッ}; natural bunsetsu
 *       spacing via {@code shouldAttachToPrev}).</li>
 *   <li>Sub-token character-by-character reading alignment ({@code splitTokenReading} /
 *       {@code splitKanaToCharReadings}) so timed Japanese words and syllables receive exact
 *       per-character romaji without rightward drift or premature sweeps.</li>
 *   <li>Contextual furigana ruby generation ({@link #enrichLyricsRuby}) as a lightweight (~17 MB)
 *       high-accuracy alternative to SudachiDict.</li>
 * </ul>
 */
public final class KuromojiRomanizer {

    public static final String DICTIONARY_NAME = "Kuromoji";

    private KuromojiRomanizer() {
    }

    /**
     * Romanizes a full Japanese lyric line using Kuromoji morphological analysis and bunsetsu
     * spacing rules.
     */
    public static String romanizeLine(String text, KuromojiTokenizer tokenizer) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<Token> tokens = tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return text;
        }

        List<String> rawRomaji = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            rawRomaji.add(tokenRomaji(token));
        }

        // Cross-token sokuon gemination (e.g. "言っ" + "て" -> "itte", "こっ" + "ち" -> "kotchi").
        List<String> resolved = applyCrossTokenSokuon(tokens, rawRomaji);

        StringBuilder out = new StringBuilder(text.length() * 2);
        Token prevNonWsToken = null;
        boolean sawPendingSpace = false;

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String rom = resolved.get(i);
            if (isWhitespaceOnly(token.surfaceForm())) {
                sawPendingSpace = true;
                continue;
            }
            if (rom.isEmpty()) {
                continue;
            }

            if (out.length() > 0) {
                boolean attach = !sawPendingSpace && shouldAttachToPrev(token, prevNonWsToken);
                if (!attach && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            }
            out.append(rom);
            prevNonWsToken = token;
            sawPendingSpace = false;
        }
        return out.toString().trim();
    }

    private record LineCharRomaji(String[] charRomaji, boolean[] bunsetsuBreakAfterChar) {
    }

    private static LineCharRomaji buildLineCharRomaji(String lineText, List<Token> tokens) {
        final int lineLen = lineText.length();
        String[] charRomaji = new String[lineLen];
        boolean[] bunsetsuBreakAfterChar = new boolean[lineLen];
        for (int i = 0; i < lineLen; i++) {
            charRomaji[i] = "";
        }

        List<String[]> tokenCharRomajis = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            tokenCharRomajis.add(perCharRomajiForToken(token));
        }

        // Resolve cross-token sokuon (token ending in っ/ッ doubles the first consonant of next token).
        for (int t = 0; t < tokens.size(); t++) {
            String surf = tokens.get(t).surfaceForm();
            if (!surf.isEmpty() && isSokuon(surf.charAt(surf.length() - 1))) {
                String[] curArr = tokenCharRomajis.get(t);
                int sokuonIdx = surf.length() - 1;
                if (sokuonIdx < curArr.length && curArr[sokuonIdx].isEmpty() && t + 1 < tokens.size()) {
                    String nextFirst = firstNonEmpty(tokenCharRomajis.get(t + 1));
                    if (!nextFirst.isEmpty()) {
                        char fc = Character.toLowerCase(nextFirst.charAt(0));
                        if (fc >= 'a' && fc <= 'z' && "aeiou".indexOf(fc) < 0 && fc != 'n') {
                            curArr[sokuonIdx] = (fc == 'c') ? "t" : String.valueOf(fc);
                        }
                    }
                }
            }
        }

        int cursor = 0;
        Token prevNonWs = null;
        int prevTokenLastChar = -1;
        boolean sawPendingSpace = false;

        for (int t = 0; t < tokens.size(); t++) {
            Token token = tokens.get(t);
            String surf = token.surfaceForm();
            int len = surf.length();
            int foundStart = !surf.isEmpty() ? lineText.indexOf(surf, cursor) : cursor;
            int start = foundStart >= 0 ? foundStart : Math.min(lineLen, cursor);
            int end = Math.min(lineLen, start + len);
            cursor = end;

            if (isWhitespaceOnly(surf)) {
                sawPendingSpace = true;
                if (prevTokenLastChar >= 0 && prevTokenLastChar < lineLen) {
                    bunsetsuBreakAfterChar[prevTokenLastChar] = true;
                }
                continue;
            }

            if (prevNonWs != null && prevTokenLastChar >= 0 && prevTokenLastChar < lineLen) {
                boolean attach = !sawPendingSpace && shouldAttachToPrev(token, prevNonWs);
                if (!attach) {
                    bunsetsuBreakAfterChar[prevTokenLastChar] = true;
                }
            }

            String[] arr = tokenCharRomajis.get(t);
            for (int k = 0; k < end - start && k < arr.length; k++) {
                charRomaji[start + k] = arr[k] != null ? arr[k] : "";
            }
            if (end > start) {
                prevTokenLastChar = end - 1;
            }
            prevNonWs = token;
            sawPendingSpace = false;
        }
        return new LineCharRomaji(charRomaji, bunsetsuBreakAfterChar);
    }

    private static String sliceCharRomaji(
            String[] charRomaji,
            boolean[] bunsetsuBreakAfterChar,
            int start,
            int end) {
        StringBuilder rom = new StringBuilder();
        for (int c = start; c < end; c++) {
            String piece = charRomaji[c];
            if (piece != null && !piece.isEmpty()) {
                rom.append(piece);
            }
            if (c < end - 1 && bunsetsuBreakAfterChar[c]
                    && rom.length() > 0 && rom.charAt(rom.length() - 1) != ' ') {
                rom.append(' ');
            }
        }
        return rom.toString().trim();
    }

    /**
     * Contextually aligns a line's timed {@link Word}s by tokenizing the full {@code lineText}
     * once and slicing character-level readings onto each {@link Word}'s span inside the line.
     */
    @Nullable
    public static List<Word> alignLineWords(
            String lineText,
            List<Word> words,
            KuromojiTokenizer tokenizer) {
        if (lineText == null || lineText.isEmpty() || words == null || words.isEmpty()) {
            return null;
        }
        List<Token> tokens = tokenizer.tokenize(lineText);
        if (tokens.isEmpty()) {
            return null;
        }

        final int lineLen = lineText.length();
        LineCharRomaji mapped = buildLineCharRomaji(lineText, tokens);
        String[] charRomaji = mapped.charRomaji;
        boolean[] bunsetsuBreakAfterChar = mapped.bunsetsuBreakAfterChar;

        // Map each `Word` onto its character span in `lineText`.
        List<Word> out = new ArrayList<>(words.size());
        int searchFrom = 0;
        boolean anyChanged = false;

        for (Word word : words) {
            String wText = word.text();
            if (wText == null || wText.isEmpty()) {
                out.add(word);
                continue;
            }
            String trimmedWord = wText.trim();
            if (trimmedWord.isEmpty()) {
                out.add(word);
                continue;
            }

            int found = lineText.indexOf(trimmedWord, searchFrom);
            if (found < 0) {
                // Fallback if brackets/whitespace differed slightly: tokenize the word directly.
                String direct = romanizeLine(trimmedWord, tokenizer);
                if (!direct.isEmpty() && !direct.equalsIgnoreCase(trimmedWord)) {
                    out.add(new Word(word.startMs(), word.endMs(), wText, direct,
                            word.endsWithSpace(), word.ruby()));
                    anyChanged = true;
                } else {
                    out.add(word);
                }
                continue;
            }

            int spanEnd = found + trimmedWord.length();
            searchFrom = spanEnd;

            // Purely Latin words keep their written text untouched (word.romaji() stays null or unchanged).
            if (isPurelyAsciiOrPunct(trimmedWord)) {
                out.add(word);
                continue;
            }

            String built = sliceCharRomaji(charRomaji, bunsetsuBreakAfterChar, found, spanEnd);
            boolean trailingSpace = word.endsWithSpace()
                    || (spanEnd > 0 && spanEnd <= lineLen && bunsetsuBreakAfterChar[spanEnd - 1])
                    || (spanEnd < lineLen && Character.isWhitespace(lineText.charAt(spanEnd)));

            if (built.isEmpty() || built.equalsIgnoreCase(trimmedWord)) {
                out.add(word);
            } else {
                out.add(new Word(word.startMs(), word.endMs(), wText,
                        trailingSpace ? built + " " : built,
                        trailingSpace, word.ruby()));
                anyChanged = true;
            }
        }

        return anyChanged ? out : null;
    }

    /**
     * Contextually reads every {@code [start, end)} character range in {@code lineText} using a
     * single Kuromoji tokenization pass so repeated kana/kanji (such as {@code は} or {@code い})
     * always receive the exact reading at their position in the line.
     */
    @Nullable
    public static String[] syllableReadingsForSlots(
            String lineText,
            int[] starts,
            int[] ends,
            KuromojiTokenizer tokenizer) {
        if (lineText == null || lineText.isEmpty() || starts == null || ends == null
                || starts.length != ends.length || starts.length == 0) {
            return null;
        }
        List<Token> tokens = tokenizer.tokenize(lineText);
        if (tokens.isEmpty()) {
            return null;
        }
        LineCharRomaji mapped = buildLineCharRomaji(lineText, tokens);
        String[] charRomaji = mapped.charRomaji;
        boolean[] bunsetsuBreakAfterChar = mapped.bunsetsuBreakAfterChar;
        final int lineLen = lineText.length();
        String[] out = new String[starts.length];

        for (int i = 0; i < starts.length; i++) {
            int s = Math.max(0, Math.min(lineLen, starts[i]));
            int e = Math.max(s, Math.min(lineLen, ends[i]));
            if (e <= s) {
                out[i] = "";
                continue;
            }
            String rawSlice = lineText.substring(s, e).trim();
            if (rawSlice.isEmpty() || isPurelyAsciiOrPunct(rawSlice)) {
                out[i] = "";
                continue;
            }
            String built = sliceCharRomaji(charRomaji, bunsetsuBreakAfterChar, s, e);
            out[i] = (!built.isEmpty() && !ScriptDetector.contains(built, Character.UnicodeScript.HAN)
                    && !built.equalsIgnoreCase(rawSlice)) ? built : "";
        }
        return out;
    }

    /**
     * Contextually reads {@code syllable} inside {@code fullLine} if {@code fullLine} contains it,
     * falling back to direct tokenization of {@code syllable}.
     */
    @Nullable
    public static String syllableReadingInContext(
            String syllable,
            @Nullable String fullLine,
            KuromojiTokenizer tokenizer) {
        if (syllable == null || syllable.isEmpty()) {
            return null;
        }
        String cleanSyl = syllable.trim();
        if (cleanSyl.isEmpty()) {
            return null;
        }
        if (fullLine != null && !fullLine.isEmpty() && !fullLine.equals(cleanSyl)) {
            int idx = fullLine.indexOf(cleanSyl);
            if (idx >= 0) {
                List<Word> single = alignLineWords(fullLine,
                        List.of(new Word(0, 1, cleanSyl)), tokenizer);
                if (single != null && !single.isEmpty() && single.get(0).romaji() != null) {
                    String r = single.get(0).romaji().trim();
                    if (!r.isEmpty() && !r.equalsIgnoreCase(cleanSyl)) {
                        return r;
                    }
                }
            }
        }
        String direct = romanizeLine(cleanSyl, tokenizer);
        return direct.isEmpty() || direct.equalsIgnoreCase(cleanSyl) ? null : direct;
    }

    /** Enriches every line containing Han characters with context-accurate furigana ruby. */
    public static Lyrics enrichLyricsRuby(
            Lyrics lyrics,
            KuromojiTokenizer tokenizer,
            RubyStyle style) {
        List<LyricsLine> out = new ArrayList<>(lyrics.lines().size());
        boolean anyChanged = false;
        for (LyricsLine line : lyrics.lines()) {
            LyricsLine enriched = enrichLineRuby(line, tokenizer, style);
            if (enriched != line) {
                anyChanged = true;
            }
            out.add(enriched);
        }
        return anyChanged ? lyrics.withLines(out) : lyrics;
    }

    public static LyricsLine enrichLineRuby(
            LyricsLine line,
            KuromojiTokenizer tokenizer,
            RubyStyle style) {
        String lineText = line.text();
        if (lineText == null || !ScriptDetector.contains(lineText, Character.UnicodeScript.HAN)) {
            return line;
        }
        List<Token> tokens = tokenizer.tokenize(lineText);
        if (tokens.isEmpty()) {
            return line;
        }

        // Case 1: Line has no word timings -> build segmented words directly from Kuromoji tokens,
        // separating kanji stems from trailing okurigana so furigana sits cleanly over the kanji!
        if (!line.hasWords()) {
            long startMs = Math.max(0L, line.startTimeMs());
            List<Word> built = buildRubyWordsFromTokens(tokens, startMs, startMs, false, style);
            return built.isEmpty() ? line : new LyricsLine(
                    line.startTimeMs(), line.endTimeMs(), lineText, built,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart());
        }

        // Case 2: Line already has timed words -> build per-char kana readings across `lineText`
        // so both per-kanji timed words (e.g. "運", "命") and compound words (e.g. "運命の", "遠回り")
        // get accurate contextual furigana without losing their word timings!
        final int lineLen = lineText.length();
        String[] charHira = new String[lineLen];
        boolean[] tokenBoundaryBefore = new boolean[lineLen + 1];
        int cursor = 0;
        for (Token token : tokens) {
            String surf = token.surfaceForm();
            int foundStart = !surf.isEmpty() ? lineText.indexOf(surf, cursor) : cursor;
            int start = foundStart >= 0 ? foundStart : Math.min(lineLen, cursor);
            int end = Math.min(lineLen, start + surf.length());
            tokenBoundaryBefore[start] = true;
            tokenBoundaryBefore[end] = true;
            cursor = end;

            String hira = KanaRomanizer.toHiragana(effectiveReading(token));
            String[] perChar = splitTokenReadingToHiragana(surf, hira);
            for (int k = 0; k < end - start && k < perChar.length; k++) {
                charHira[start + k] = perChar[k];
            }
        }

        List<Word> enrichedWords = new ArrayList<>(line.words().size() + 4);
        int searchFrom = 0;
        boolean changed = false;

        for (Word word : line.words()) {
            String wText = word.text();
            if (wText == null || wText.isEmpty()) {
                enrichedWords.add(word);
                continue;
            }
            if (word.ruby() != null && !word.ruby().isEmpty()) {
                enrichedWords.add(word);
                int idx = lineText.indexOf(wText.trim(), searchFrom);
                if (idx >= 0) searchFrom = idx + wText.trim().length();
                continue;
            }

            String trimmed = wText.trim();
            int found = trimmed.isEmpty() ? -1 : lineText.indexOf(trimmed, searchFrom);
            if (found >= 0) {
                searchFrom = found + trimmed.length();
            }

            if (!ScriptDetector.contains(trimmed, Character.UnicodeScript.HAN)) {
                enrichedWords.add(word);
                continue;
            }

            if (found >= 0) {
                List<Word> subWords = sliceTimedWordIntoRubyRuns(
                        word, lineText, found, found + trimmed.length(), charHira,
                        tokenBoundaryBefore, style);
                if (!subWords.isEmpty()) {
                    enrichedWords.addAll(subWords);
                    changed = true;
                    continue;
                }
            }

            // Fallback: tokenize just this word, preserving any original per-word romaji.
            List<Token> wordTokens = tokenizer.tokenize(trimmed);
            List<Word> fallback = buildRubyWordsFromTokens(
                    wordTokens, word.startMs(), word.endMs(), word.endsWithSpace(), style);
            if (!fallback.isEmpty()) {
                distributeOriginalRomajiToRubyWords(word.romaji(), fallback);
                enrichedWords.addAll(fallback);
                changed = true;
            } else {
                enrichedWords.add(word);
            }
        }

        return changed
                ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), lineText, enrichedWords,
                line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                : line;
    }

    private static List<Word> sliceTimedWordIntoRubyRuns(
            Word originalWord,
            String lineText,
            int spanStart,
            int spanEnd,
            String[] charHira,
            boolean[] tokenBoundaryBefore,
            RubyStyle style) {
        // Group consecutive Han characters within the same Kuromoji token into one Kanji run,
        // and consecutive non-Han characters into their own bare runs (so okurigana is NOT under furigana!).
        record SubRun(int start, int end, boolean isHan) {
        }
        List<SubRun> runs = new ArrayList<>(4);
        int i = spanStart;
        while (i < spanEnd) {
            boolean han = ScriptDetector.isHan(lineText.charAt(i));
            int j = i + 1;
            if (han) {
                while (j < spanEnd && ScriptDetector.isHan(lineText.charAt(j))
                        && !tokenBoundaryBefore[j]) {
                    j++;
                }
            } else {
                while (j < spanEnd && !ScriptDetector.isHan(lineText.charAt(j))) {
                    j++;
                }
            }
            runs.add(new SubRun(i, j, han));
            i = j;
        }

        if (runs.size() == 1 && runs.get(0).isHan) {
            StringBuilder hira = new StringBuilder();
            for (int c = spanStart; c < spanEnd; c++) {
                if (charHira[c] != null) hira.append(charHira[c]);
            }
            String rubyStr = hira.toString();
            if (rubyStr.isEmpty() || rubyStr.equalsIgnoreCase(originalWord.text().trim())) {
                return Collections.emptyList();
            }
            return List.of(new Word(
                    originalWord.startMs(),
                    originalWord.endMs(),
                    originalWord.text(),
                    originalWord.romaji(),
                    originalWord.endsWithSpace(),
                    RubyGenerator.formatRuby(rubyStr, style)));
        }

        // Multiple runs inside one word (e.g. "遠回りして" -> "遠回"[とおまわ] + "りして", or "真白な" -> "真白"[まっしろ] + "な"):
        int totalChars = spanEnd - spanStart;
        long wStart = originalWord.startMs();
        long duration = Math.max(0L, originalWord.endMs() - wStart);
        long cursorMs = wStart;
        int seen = 0;
        List<Word> out = new ArrayList<>(runs.size());
        boolean anyRuby = false;

        // Preserve originalWord.romaji() (e.g. embedded/provider reading like "masshirona" or "yattekimashita")
        // by distributing it across the sliced Han/kana runs using each run's hiragana reading as a guide.
        String origRom = originalWord.romaji();
        String trimmedOrigRom = origRom != null ? origRom.trim() : "";
        String[] runHiras = new String[runs.size()];
        String[] runRoms = new String[runs.size()];
        String[] runTexts = new String[runs.size()];
        List<String> runGuides = new ArrayList<>(runs.size());
        for (int r = 0; r < runs.size(); r++) {
            SubRun run = runs.get(r);
            String subText = lineText.substring(run.start, run.end);
            runTexts[r] = subText;
            if (run.isHan) {
                StringBuilder hira = new StringBuilder();
                for (int c = run.start; c < run.end; c++) {
                    if (charHira[c] != null) hira.append(charHira[c]);
                }
                runHiras[r] = hira.toString();
                runGuides.add(runHiras[r].isEmpty() ? "" : romanizeRevisedHepburn(runHiras[r]));
            } else {
                runHiras[r] = "";
                runGuides.add(ScriptDetector.containsKana(subText) ? romanizeRevisedHepburn(subText) : subText);
            }
        }
        if (!trimmedOrigRom.isEmpty()) {
            boolean[] latinFlags = new boolean[runs.size()];
            JapaneseMoraSplitter.AlignedChunk[] aligned = JapaneseMoraSplitter.alignJapaneseLineToSlots(
                    trimmedOrigRom, runTexts, latinFlags, runGuides);
            if (aligned != null && aligned.length == runs.size()) {
                for (int r = 0; r < runs.size(); r++) {
                    String t = aligned[r].text() != null ? aligned[r].text().trim() : "";
                    if (!t.isEmpty()) {
                        runRoms[r] = aligned[r].trailingSpace() ? (t + " ") : t;
                    }
                }
            } else {
                List<String> split = JapaneseMoraSplitter.partitionMorasToSlots(
                        trimmedOrigRom, runs.size(), runGuides, runTexts);
                for (int r = 0; r < runs.size() && r < split.size(); r++) {
                    String piece = split.get(r) != null ? split.get(r).trim() : "";
                    if (!piece.isEmpty()) {
                        runRoms[r] = piece;
                    }
                }
            }
            // Never leave a readable run empty if we had an original word reading.
            for (int r = 0; r < runs.size(); r++) {
                if ((runRoms[r] == null || runRoms[r].trim().isEmpty())
                        && r < runGuides.size() && !runGuides.get(r).isEmpty()) {
                    runRoms[r] = runGuides.get(r);
                }
            }
        }

        for (int r = 0; r < runs.size(); r++) {
            SubRun run = runs.get(r);
            seen += (run.end - run.start);
            long rEnd = (r == runs.size() - 1)
                    ? originalWord.endMs()
                    : (wStart + duration * seen / Math.max(1, totalChars));
            String subText = runTexts[r];
            boolean trailSpace = (r == runs.size() - 1) && originalWord.endsWithSpace();
            String ruby = null;
            if (run.isHan) {
                String hira = runHiras[r];
                if (!hira.isEmpty() && !hira.equalsIgnoreCase(subText)) {
                    ruby = RubyGenerator.formatRuby(hira, style);
                    anyRuby = true;
                }
            }
            out.add(new Word(cursorMs, rEnd, subText, runRoms[r], trailSpace, ruby));
            cursorMs = rEnd;
        }
        return anyRuby ? out : Collections.emptyList();
    }

    private static void distributeOriginalRomajiToRubyWords(@Nullable String origRom, List<Word> words) {
        if (origRom == null || origRom.trim().isEmpty() || words.isEmpty()) {
            return;
        }
        String trimmedOrig = origRom.trim();
        int count = words.size();
        String[] subTexts = new String[count];
        List<String> guides = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Word w = words.get(i);
            subTexts[i] = w.text();
            String gSrc = (w.ruby() != null && !w.ruby().isEmpty()) ? w.ruby() : w.text();
            guides.add(ScriptDetector.containsKana(gSrc) ? romanizeRevisedHepburn(gSrc) : gSrc);
        }
        String[] roms = new String[count];
        JapaneseMoraSplitter.AlignedChunk[] aligned = JapaneseMoraSplitter.alignJapaneseLineToSlots(
                trimmedOrig, subTexts, new boolean[count], guides);
        if (aligned != null && aligned.length == count) {
            for (int i = 0; i < count; i++) {
                String t = aligned[i].text() != null ? aligned[i].text().trim() : "";
                if (!t.isEmpty()) {
                    roms[i] = aligned[i].trailingSpace() ? (t + " ") : t;
                }
            }
        } else {
            List<String> split = JapaneseMoraSplitter.partitionMorasToSlots(
                    trimmedOrig, count, guides, subTexts);
            for (int i = 0; i < count && i < split.size(); i++) {
                String piece = split.get(i) != null ? split.get(i).trim() : "";
                if (!piece.isEmpty()) {
                    roms[i] = piece;
                }
            }
        }
        for (int i = 0; i < count; i++) {
            Word w = words.get(i);
            String r = roms[i];
            if ((r == null || r.trim().isEmpty()) && !guides.get(i).isEmpty()) {
                r = guides.get(i);
            }
            if (r != null && !r.isEmpty()) {
                words.set(i, new Word(w.startMs(), w.endMs(), w.text(), r, w.endsWithSpace(), w.ruby()));
            }
        }
    }

    private static List<Word> buildRubyWordsFromTokens(
            List<Token> tokens,
            long startMs,
            long endMs,
            boolean finalSpace,
            RubyStyle style) {
        record Piece(String text, @Nullable String hiraRuby, boolean spaceAfter) {
        }
        List<Piece> pieces = new ArrayList<>(tokens.size() * 2);

        for (int t = 0; t < tokens.size(); t++) {
            Token tok = tokens.get(t);
            String surf = tok.surfaceForm();
            if (surf.isEmpty()) continue;
            if (isWhitespaceOnly(surf)) {
                if (!pieces.isEmpty()) {
                    int lastIdx = pieces.size() - 1;
                    Piece p = pieces.get(lastIdx);
                    pieces.set(lastIdx, new Piece(p.text, p.hiraRuby, true));
                }
                continue;
            }
            if (!ScriptDetector.contains(surf, Character.UnicodeScript.HAN)) {
                pieces.add(new Piece(surf, null, false));
                continue;
            }

            String hira = KanaRomanizer.toHiragana(effectiveReading(tok));
            String[] perChar = splitTokenReadingToHiragana(surf, hira);
            int i = 0;
            while (i < surf.length()) {
                boolean han = ScriptDetector.isHan(surf.charAt(i));
                int j = i + 1;
                while (j < surf.length() && ScriptDetector.isHan(surf.charAt(j)) == han) {
                    j++;
                }
                String sub = surf.substring(i, j);
                if (han) {
                    StringBuilder r = new StringBuilder();
                    for (int k = i; k < j && k < perChar.length; k++) {
                        if (perChar[k] != null) r.append(perChar[k]);
                    }
                    String rubyStr = r.length() > 0 ? RubyGenerator.formatRuby(r.toString(), style) : null;
                    pieces.add(new Piece(sub, rubyStr, false));
                } else {
                    pieces.add(new Piece(sub, null, false));
                }
                i = j;
            }
        }

        if (pieces.isEmpty()) {
            return Collections.emptyList();
        }
        int totalChars = 0;
        for (Piece p : pieces) totalChars += p.text.length();
        long duration = Math.max(0L, endMs - startMs);
        long cursorMs = startMs;
        int seen = 0;
        List<Word> words = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            seen += p.text.length();
            long wEnd = (i == pieces.size() - 1)
                    ? endMs
                    : (startMs + duration * seen / Math.max(1, totalChars));
            boolean sp = p.spaceAfter || (i == pieces.size() - 1 && finalSpace);
            words.add(new Word(cursorMs, wEnd, p.text, null, sp, p.hiraRuby));
            cursorMs = wEnd;
        }
        return words;
    }

    private static String tokenRomaji(Token token) {
        String surface = token.surfaceForm();
        if (surface.isEmpty()) {
            return "";
        }
        // Grammatical particles read phonetically: は -> wa, へ -> e, を -> o
        if ("助詞".equals(token.pos())) {
            switch (surface) {
                case "は" -> {
                    return "wa";
                }
                case "へ" -> {
                    return "e";
                }
                case "を" -> {
                    return "o";
                }
                default -> {
                }
            }
        }
        // Interjections こんにちは / こんばんは use IPADIC pronunciation ("コンニチワ" / "コンバンワ").
        if ("感動詞".equals(token.pos()) && !token.pronunciation().isEmpty()
                && ("こんにちは".equals(surface) || "こんばんは".equals(surface))) {
            return romanizeRevisedHepburn(token.pronunciation());
        }

        String reading = effectiveReading(token);
        if (!reading.isEmpty() && ScriptDetector.containsKana(reading)) {
            // Preserve dangling trailing sokuon for cross-token resolution:
            // KanaRomanizer drops a trailing っ/ッ if nothing follows it.
            if (isSokuon(reading.charAt(reading.length() - 1)) && reading.length() > 1) {
                return romanizeRevisedHepburn(reading.substring(0, reading.length() - 1));
            }
            if (reading.length() == 1 && isSokuon(reading.charAt(0))) {
                return "";
            }
            return romanizeRevisedHepburn(reading);
        }
        if (ScriptDetector.containsKana(surface)) {
            if (surface.length() == 1 && isSokuon(surface.charAt(0))) {
                return "";
            }
            return romanizeRevisedHepburn(surface);
        }
        return surface;
    }

    /**
     * Converts kana to Revised Hepburn romaji
     * (syllabic {@code ん/ン} is always written {@code "n"}, e.g. {@code unmei}, {@code konbanwa},
     * {@code shinbun}, rather than Traditional Hepburn {@code m}).
     */
    private static String romanizeRevisedHepburn(String kana) {
        String[] moras = splitKanaToCharReadings(KanaRomanizer.toHiragana(kana));
        String[] pieces = convertCharHiraganaToRomaji(moras);
        StringBuilder sb = new StringBuilder(kana.length() * 2);
        for (String piece : pieces) {
            sb.append(piece);
        }
        return sb.toString();
    }

    private static String effectiveReading(Token token) {
        if (!token.reading().isEmpty()) {
            return token.reading();
        }
        String surf = token.surfaceForm();
        if (ScriptDetector.containsKana(surf) && !ScriptDetector.contains(surf, Character.UnicodeScript.HAN)) {
            return surf;
        }
        return "";
    }

    private static List<String> applyCrossTokenSokuon(List<Token> tokens, List<String> romaji) {
        List<String> out = new ArrayList<>(romaji);
        for (int i = 0; i < tokens.size(); i++) {
            String surf = tokens.get(i).surfaceForm();
            String read = effectiveReading(tokens.get(i));
            boolean endsWithSokuon = (!surf.isEmpty() && isSokuon(surf.charAt(surf.length() - 1)))
                    || (!read.isEmpty() && isSokuon(read.charAt(read.length() - 1)));
            if (!endsWithSokuon || i + 1 >= tokens.size()) {
                continue;
            }
            String nextRom = out.get(i + 1);
            if (nextRom == null || nextRom.isEmpty()) {
                continue;
            }
            char fc = Character.toLowerCase(nextRom.charAt(0));
            if (fc >= 'a' && fc <= 'z' && "aeiou".indexOf(fc) < 0 && fc != 'n') {
                char doubled = (fc == 'c') ? 't' : fc; // "cchi" -> "tchi"
                out.set(i, out.get(i) + doubled);
            }
        }
        return out;
    }

    /**
     * Determines whether {@code token} attaches to {@code prevToken} for natural Japanese bunsetsu
     * spacing.
     */
    static boolean shouldAttachToPrev(Token token, @Nullable Token prevToken) {
        if (prevToken == null) {
            return true;
        }
        String pos = token.pos();
        String detail1 = token.posDetail1();
        String surf = token.surfaceForm();

        // Closing punctuation attaches to previous word; opening brackets do NOT attach to previous word,
        // instead the word AFTER an opening bracket attaches to the opening bracket!
        if ("記号".equals(pos)) {
            if ("括弧開".equals(detail1) || isOpeningBracket(surf)) {
                return false;
            }
            return !"空白".equals(detail1);
        }
        if ("記号".equals(prevToken.pos())
                && ("括弧開".equals(prevToken.posDetail1()) || isOpeningBracket(prevToken.surfaceForm()))) {
            return true;
        }

        // Prefixes (お, ご, 第, 超) start a bunsetsu; the following stem attaches to the prefix!
        if ("接頭詞".equals(prevToken.pos())) {
            return true;
        }

        // Cross-token sokuon attaches tightly (e.g. 言っ + て -> itte, こっ + ち -> kotchi).
        String prevSurf = prevToken.surfaceForm();
        if (!prevSurf.isEmpty() && isSokuon(prevSurf.charAt(prevSurf.length() - 1))) {
            return true;
        }

        // Auxiliary verbs (た, だ, ない, ます, です, れる, られる, たい):
        // Attach to verbs/adjectives/auxiliaries ("愛してた" -> "aishiteta") and colloquial
        // negative contractions ("じゃない" -> "janai"), keeping copula です/だ separate after nouns!
        if ("助動詞".equals(pos)) {
            if (("です".equals(surf) || "だ".equals(surf) || "でし".equals(surf) || "だろ".equals(surf))
                    && "名詞".equals(prevToken.pos())) {
                return false;
            }
            return "動詞".equals(prevToken.pos())
                    || "形容詞".equals(prevToken.pos())
                    || "助動詞".equals(prevToken.pos())
                    || ("助詞".equals(prevToken.pos())
                    && ("接続助詞".equals(prevToken.posDetail1())
                    || ("ない".equals(surf) && ("じゃ".equals(prevSurf) || "ちゃ".equals(prevSurf)))));
        }

        // Conjunctive particles (接続助詞: て, で, ば, たら, たり, ながら, etc.) attach to verb/adj/suffix/aux!
        if ("助詞".equals(pos) && "接続助詞".equals(detail1)) {
            return "動詞".equals(prevToken.pos())
                    || "形容詞".equals(prevToken.pos())
                    || "助動詞".equals(prevToken.pos())
                    || "接尾".equals(prevToken.posDetail1());
        }

        // Particle attached to particle (に + は -> niwa, で + は -> dewa, て + も -> temo).
        if ("助詞".equals(pos) && "助詞".equals(prevToken.pos())) {
            return true;
        }

        // Dependent / suffix verbs & adjectives (e.g. してる = し + て + る, しまう, いく, くる after て/で, or 接尾)
        if ("非自立".equals(detail1)
                && ("動詞".equals(prevToken.pos())
                || "接続助詞".equals(prevToken.posDetail1())
                || "て".equals(prevSurf)
                || "で".equals(prevSurf))) {
            return true;
        }
        if ("接尾".equals(detail1)) {
            return true;
        }

        // Indefinite pronouns (何か -> nanika, 誰も -> daremo, どこか -> dokoka, いつも -> itsumo)
        if ("か".equals(surf) && "何".equals(prevSurf)) {
            return true;
        }
        return "も".equals(surf) && ("誰".equals(prevSurf) || "何".equals(prevSurf)
                || "いつ".equals(prevSurf) || "どこ".equals(prevSurf));
    }

    /**
     * Splits a token's reading into one romaji piece per UTF-16 character of {@code token.surfaceForm()}.
     */
    static String[] perCharRomajiForToken(Token token) {
        String surf = token.surfaceForm();
        int n = surf.length();
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = "";
        if (n == 0) return out;

        // Special single-kana particles
        if ("助詞".equals(token.pos()) && n == 1) {
            switch (surf) {
                case "は" -> {
                    out[0] = "wa";
                    return out;
                }
                case "へ" -> {
                    out[0] = "e";
                    return out;
                }
                case "を" -> {
                    out[0] = "o";
                    return out;
                }
                default -> {
                }
            }
        }
        if ("感動詞".equals(token.pos()) && !token.pronunciation().isEmpty()
                && ("こんにちは".equals(surf) || "こんばんは".equals(surf))) {
            String[] hiraChars = splitKanaToCharReadings(KanaRomanizer.toHiragana(token.pronunciation()));
            for (int i = 0; i < n && i < hiraChars.length; i++) {
                out[i] = KanaRomanizer.romanize(hiraChars[i]);
            }
            return out;
        }

        // Pure non-Japanese (Latin / digits / punctuation)
        if (!ScriptDetector.containsKana(surf) && !ScriptDetector.contains(surf, Character.UnicodeScript.HAN)) {
            for (int i = 0; i < n; i++) {
                out[i] = String.valueOf(surf.charAt(i));
            }
            return out;
        }

        String hiraReading = KanaRomanizer.toHiragana(effectiveReading(token));
        if (hiraReading.isEmpty()) {
            if (ScriptDetector.containsKana(surf)) {
                hiraReading = KanaRomanizer.toHiragana(surf);
            } else {
                out[0] = surf;
                return out;
            }
        }

        String[] charHira = splitTokenReadingToHiragana(surf, hiraReading);
        return convertCharHiraganaToRomaji(charHira);
    }

    /**
     * Converts per-character hiragana slices (such as {@code ["とお", "まわ", "り"]} or
     * {@code ["き", "っ", "て"]}) into per-character Revised Hepburn romaji while preserving
     * cross-slice sokuon gemination and {@code ー} vowel elongation!
     */
    static String[] convertCharHiraganaToRomaji(String[] charHira) {
        final int n = charHira.length;
        String[] out = new String[n];
        char lastVowel = 0;

        for (int i = 0; i < n; i++) {
            String h = charHira[i] != null ? charHira[i] : "";
            if (h.isEmpty()) {
                out[i] = "";
                continue;
            }

            // Single standalone sokuon: double the opening consonant of the next non-empty slice!
            if (h.length() == 1 && isSokuon(h.charAt(0))) {
                String nextHira = "";
                for (int k = i + 1; k < n; k++) {
                    if (charHira[k] != null && !charHira[k].isEmpty()) {
                        nextHira = charHira[k];
                        break;
                    }
                }
                if (!nextHira.isEmpty()) {
                    String nextRom = KanaRomanizer.romanize(nextHira);
                    if (!nextRom.isEmpty()) {
                        char fc = Character.toLowerCase(nextRom.charAt(0));
                        if (fc >= 'a' && fc <= 'z' && "aeiou".indexOf(fc) < 0 && fc != 'n') {
                            out[i] = (fc == 'c') ? "t" : String.valueOf(fc);
                            continue;
                        }
                    }
                }
                out[i] = "";
                continue;
            }

            // Single chōonpu 'ー' repeats the preceding slice's final vowel!
            if ("ー".equals(h) || "—".equals(h)) {
                out[i] = lastVowel != 0 ? String.valueOf(lastVowel) : "";
                continue;
            }

            // Check if `h` ends with sokuon (e.g. kanji reading "がっ" before "こう" in 学校 -> "gak" + "kou")!
            boolean trailingSokuon = h.length() > 1 && isSokuon(h.charAt(h.length() - 1));
            String coreHira = trailingSokuon ? h.substring(0, h.length() - 1) : h;

            // Romanize mora-by-mora inside `coreHira` so syllabic 'ん' stays Revised Hepburn "n"
            // (e.g. "うんめい" -> "unmei") while internal sokuon/chōonpu still resolve accurately!
            String rom;
            if (coreHira.indexOf('ん') >= 0 && coreHira.length() > 1) {
                StringBuilder coreSb = new StringBuilder(coreHira.length() * 2);
                int segStart = 0;
                for (int idx = 0; idx < coreHira.length(); idx++) {
                    if (coreHira.charAt(idx) == 'ん') {
                        if (idx > segStart) {
                            coreSb.append(KanaRomanizer.romanize(coreHira.substring(segStart, idx)));
                        }
                        coreSb.append('n');
                        segStart = idx + 1;
                    }
                }
                if (segStart < coreHira.length()) {
                    coreSb.append(KanaRomanizer.romanize(coreHira.substring(segStart)));
                }
                rom = coreSb.toString();
            } else {
                rom = KanaRomanizer.romanize(coreHira);
            }

            if (trailingSokuon) {
                for (int k = i + 1; k < n; k++) {
                    if (charHira[k] != null && !charHira[k].isEmpty()) {
                        String nextRom = KanaRomanizer.romanize(charHira[k]);
                        if (!nextRom.isEmpty()) {
                            char fc = Character.toLowerCase(nextRom.charAt(0));
                            if (fc >= 'a' && fc <= 'z' && "aeiou".indexOf(fc) < 0 && fc != 'n') {
                                rom = rom + ((fc == 'c') ? 't' : fc);
                            }
                        }
                        break;
                    }
                }
            }

            out[i] = rom;
            for (int v = rom.length() - 1; v >= 0; v--) {
                char ch = Character.toLowerCase(rom.charAt(v));
                if ("aeiou".indexOf(ch) >= 0) {
                    lastVowel = ch;
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Maps a token's hiragana reading onto each character of {@code surface} (kanji + okurigana +
     * combining small kana).
     */
    static String[] splitTokenReadingToHiragana(String surface, String readingHira) {
        final int n = surface.length();
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = "";
        if (n == 0 || readingHira == null || readingHira.isEmpty()) {
            return out;
        }
        if (n == 1) {
            out[0] = readingHira;
            return out;
        }

        // Pure kana surface (hiragana / katakana): combine small ゃゅょぁぃぅぇぉ onto their base kana!
        if (!ScriptDetector.contains(surface, Character.UnicodeScript.HAN)) {
            String surfHira = KanaRomanizer.toHiragana(surface);
            if (surfHira.length() == n && readingHira.length() == n) {
                int i = 0;
                while (i < n) {
                    int end = i + 1;
                    while (end < n && JapaneseMoraSplitter.isSmallCombiningKana(surface.charAt(end))) {
                        end++;
                    }
                    out[i] = readingHira.substring(i, end);
                    i = end;
                }
                return out;
            }
            String[] moras = splitKanaToCharReadings(readingHira);
            int mIdx = 0;
            int i = 0;
            while (i < n && mIdx < moras.length) {
                int end = i + 1;
                while (end < n && JapaneseMoraSplitter.isSmallCombiningKana(surface.charAt(end))) {
                    end++;
                }
                out[i] = moras[mIdx++];
                i = end;
            }
            while (mIdx < moras.length) {
                out[n - 1] = out[n - 1] + moras[mIdx++];
            }
            return out;
        }

        // Mixed Kanji + Okurigana (e.g. "遠回り" + "とおまわり", "見つけた" + "みつけた", "お祝い" + "おいわい"):
        // 1. Strip matching trailing kana suffix.
        String surfHira = KanaRomanizer.toHiragana(surface);
        int sLeft = 0;
        int sRight = n;
        int rLeft = 0;
        int rRight = readingHira.length();

        while (sRight > sLeft && rRight > rLeft
                && ScriptDetector.isKana(surface.charAt(sRight - 1))
                && surfHira.charAt(sRight - 1) == readingHira.charAt(rRight - 1)) {
            out[sRight - 1] = String.valueOf(readingHira.charAt(rRight - 1));
            sRight--;
            rRight--;
        }
        // Merge any combining small kana in the stripped suffix onto its preceding kana char!
        for (int k = n - 1; k > sRight; k--) {
            if (JapaneseMoraSplitter.isSmallCombiningKana(surface.charAt(k)) && !out[k].isEmpty()) {
                out[k - 1] = out[k - 1] + out[k];
                out[k] = "";
            }
        }

        // 2. Strip matching leading kana prefix (e.g. "お" in "お祝い").
        while (sLeft < sRight && rLeft < rRight
                && ScriptDetector.isKana(surface.charAt(sLeft))
                && surfHira.charAt(sLeft) == readingHira.charAt(rLeft)) {
            out[sLeft] = String.valueOf(readingHira.charAt(rLeft));
            sLeft++;
            rLeft++;
        }

        // 3. Also check internal kana anchor inside compound (e.g. "振り向く" -> "振" + "り" + "向" + "く"
        //    if tokenized as 1 compound word!).
        String midSurf = surface.substring(sLeft, sRight);
        String midRead = readingHira.substring(rLeft, rRight);
        distributeKanjiAndInternalKana(midSurf, midRead, out, sLeft);
        return out;
    }

    private static void distributeKanjiAndInternalKana(
            String surf, String readingHira, String[] out, int offset) {
        final int len = surf.length();
        if (len == 0 || readingHira.isEmpty()) {
            return;
        }
        if (len == 1) {
            out[offset] = readingHira;
            return;
        }

        // Look for an internal kana run (e.g. "り" in "取り戻す" -> ["取", "り", "戻"] after stripping "す")
        for (int i = 1; i < len - 1; i++) {
            if (ScriptDetector.isKana(surf.charAt(i))) {
                int kanaEnd = i + 1;
                while (kanaEnd < len - 1 && ScriptDetector.isKana(surf.charAt(kanaEnd))) {
                    kanaEnd++;
                }
                String kanaAnchor = KanaRomanizer.toHiragana(surf.substring(i, kanaEnd));
                // Search for `kanaAnchor` inside `readingHira` leaving at least 1 char on each side!
                int anchorPos = readingHira.indexOf(kanaAnchor, 1);
                if (anchorPos >= 1 && anchorPos + kanaAnchor.length() <= readingHira.length() - 1) {
                    distributePureKanjiReading(surf.substring(0, i), readingHira.substring(0, anchorPos), out, offset);
                    for (int k = 0; k < kanaAnchor.length(); k++) {
                        out[offset + i + k] = String.valueOf(kanaAnchor.charAt(k));
                    }
                    distributeKanjiAndInternalKana(
                            surf.substring(kanaEnd),
                            readingHira.substring(anchorPos + kanaAnchor.length()),
                            out, offset + kanaEnd);
                    return;
                }
            }
        }

        distributePureKanjiReading(surf, readingHira, out, offset);
    }

    /**
     * Distributes {@code readingHira} across {@code kanjiSurf.length()} kanji characters using
     * coda-aware moraic rules ({@code [2,2]}, {@code [1,2]}/{@code [2,1]} with
     * {@code ん/っ/ー/う/い} coda affinity).
     */
    private static void distributePureKanjiReading(
            String kanjiSurf, String readingHira, String[] out, int offset) {
        final int kCount = kanjiSurf.length();
        if (kCount <= 0 || readingHira.isEmpty()) {
            return;
        }
        if (kCount == 1) {
            out[offset] = readingHira;
            return;
        }

        String[] moras = splitKanaToCharReadings(readingHira);
        final int mCount = moras.length;

        if (mCount == kCount) {
            System.arraycopy(moras, 0, out, offset, kCount);
            return;
        }

        // Coda-aware 2-kanji compound heuristics:
        if (kCount == 2) {
            if (mCount == 4) {
                // 4 moras over 2 kanji (e.g. 遠回 -> とお + まわ, 運命 -> うん + めい, 学校 -> がっ + こう): [2, 2]!
                out[offset] = moras[0] + moras[1];
                out[offset + 1] = moras[2] + moras[3];
                return;
            }
            if (mCount == 3) {
                // Check if mora[1] is a coda/lengthener belonging to kanji 0 (ん, っ, ー, or long vowel):
                // e.g. 安心 (あんしん=4), 天気 (てんき -> てん + き [2, 1]!), 世界 (せかい -> せ + かい [1, 2]!),
                // 太陽 (たいよう=4), 未来 (みらい -> み + らい [1, 2]!), 最初 (さいしょ -> さい + しょ [2, 1]!).
                if (isCodaOrLongSecondMora(moras[0], moras[1])) {
                    out[offset] = moras[0] + moras[1];
                    out[offset + 1] = moras[2];
                } else {
                    out[offset] = moras[0];
                    out[offset + 1] = moras[1] + moras[2];
                }
                return;
            }
        }

        if (mCount > kCount) {
            // Merge dependent moras (っ, ん, ー) onto their preceding mora first!
            List<String> units = new ArrayList<>(mCount);
            for (int mi = 0; mi < moras.length; mi++) {
                String m = moras[mi];
                int remainingMoras = moras.length - 1 - mi;
                if (!units.isEmpty() && isDependentCodaMora(m) && (units.size() + remainingMoras >= kCount)) {
                    int last = units.size() - 1;
                    units.set(last, units.get(last) + m);
                } else {
                    units.add(m);
                }
            }
            int uCount = units.size();
            if (uCount >= kCount) {
                int prev = 0;
                for (int i = 0; i < kCount; i++) {
                    int end = (i == kCount - 1) ? uCount : Math.max(prev + 1, (i + 1) * uCount / kCount);
                    end = Math.min(end, uCount - (kCount - 1 - i));
                    StringBuilder sb = new StringBuilder();
                    for (int j = prev; j < end; j++) sb.append(units.get(j));
                    out[offset + i] = sb.toString();
                    prev = end;
                }
                return;
            }
        }

        // Fewer moras than kanji (irregular jukujikun like 今日="きょう" [2 moras: "きょ","う"]):
        for (int i = 0; i < kCount; i++) {
            out[offset + i] = i < mCount ? moras[i] : "";
        }
    }

    /**
     * Splits hiragana/katakana into moraic units where small combining kana ({@code ゃゅょぁぃぅぇぉ})
     * stay attached to their preceding kana character ({@code "きょう" -> ["きょ", "う"]}).
     */
    static String[] splitKanaToCharReadings(String kana) {
        if (kana == null || kana.isEmpty()) {
            return new String[0];
        }
        List<String> units = new ArrayList<>(kana.length());
        int i = 0;
        final int n = kana.length();
        while (i < n) {
            int end = i + 1;
            while (end < n && JapaneseMoraSplitter.isSmallCombiningKana(kana.charAt(end))) {
                end++;
            }
            units.add(kana.substring(i, end));
            i = end;
        }
        return units.toArray(new String[0]);
    }

    private static boolean isDependentCodaMora(String mora) {
        return "ん".equals(mora) || "っ".equals(mora) || "ー".equals(mora)
                || "ン".equals(mora) || "ッ".equals(mora);
    }

    private static boolean isCodaOrLongSecondMora(String firstMora, String secondMora) {
        if (isDependentCodaMora(secondMora)) {
            return true;
        }
        if (secondMora.length() != 1 || firstMora.isEmpty()) {
            return false;
        }
        char v2 = secondMora.charAt(0);
        String r1 = KanaRomanizer.romanize(firstMora);
        if (r1.isEmpty()) return false;
        char lastV1 = r1.charAt(r1.length() - 1);
        // o + u (こう/とう), u + u (ゆう), e + i (せい/れい), a + i in さい/たい/がい -> [2, 1]!
        return (lastV1 == 'o' && (v2 == 'う' || v2 == 'お'))
                || (lastV1 == 'u' && v2 == 'う')
                || (lastV1 == 'e' && (v2 == 'い' || v2 == 'え'));
    }

    private static boolean isSokuon(char c) {
        return c == 'っ' || c == 'ッ';
    }

    private static boolean isOpeningBracket(String s) {
        return "「".equals(s) || "『".equals(s) || "（".equals(s) || "(".equals(s)
                || "【".equals(s) || "[".equals(s) || "〈".equals(s) || "《".equals(s);
    }

    private static boolean isWhitespaceOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isPurelyAsciiOrPunct(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80 && (Character.isLetter(c) || ScriptDetector.isKana(c) || ScriptDetector.isHan(c))) {
                return false;
            }
        }
        return true;
    }

    private static String firstNonEmpty(String[] arr) {
        for (String s : arr) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }
}

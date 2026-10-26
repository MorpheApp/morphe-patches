/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Paint;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.ArmenianRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.CyrillicRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.GeorgianRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.GreekRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.HangulRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

final class LyricsSpanBuilder {
    static final float TRANSLATION_RELATIVE_SIZE = 0.7f;
    static final float MAIN_LINE_SPACING = 1.22f;

    record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji, boolean hasTrailingSpace) {

        WordTiming(int start, int end, long startMs, long endMs, @Nullable String romaji) {
            this(start, end, startMs, endMs, romaji,
                    romaji != null && romaji.endsWith(" "));
        }
    }

    record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd,
            @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords,
            boolean showSyllableRomanization) {

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, null, false, false);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd,
                @Nullable List<WordTiming> readingSpans) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, readingSpans, false, false);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd,
                @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                    readingSpans, smallRunHoldsWords, false);
        }
    }

    private record Spacing(int mainClear, int smallClear, int boundaryClear) {
    }

    record PresentedLine(BuildResult display, List<WordTiming> words, int originalStart) {
    }

    /**
     * Applies the lyrics text filters to a built line, and moves its spans and word offsets to
     * where they are in the filtered text.
     *
     * @param words Word offsets relative to originalStart.
     */
    static PresentedLine present(BuildResult raw, List<WordTiming> words, int originalStart) {
        if (!LyricsPresentation.isFormattingEnabled()) {
            return new PresentedLine(raw, words, originalStart);
        }
        final LyricsTextFormatter.Result formatted = LyricsPresentation.format(raw.text().toString());
        if (formatted.text().contentEquals(raw.text())) {
            return new PresentedLine(raw, words, originalStart);
        }
        final SpannableString text = new SpannableString(formatted.text());
        for (Object span : raw.text().getSpans(0, raw.text().length(), Object.class)) {
            final int start = formatted.offset(raw.text().getSpanStart(span));
            final int end = formatted.offset(raw.text().getSpanEnd(span));
            if (end > start) text.setSpan(span, start, end, raw.text().getSpanFlags(span));
        }
        final int base = formatted.offset(originalStart);
        final List<WordTiming> mapped = new ArrayList<>(words.size());
        for (WordTiming word : words) {
            final int start = formatted.offset(word.start() + originalStart) - base;
            final int end = formatted.offset(word.end() + originalStart) - base;
            if (end > start) mapped.add(new WordTiming(start, end, word.startMs(), word.endMs(), word.romaji()));
        }
        return new PresentedLine(new BuildResult(text, raw.unsungSpan(),
                formatted.offset(raw.transStart()), formatted.offset(raw.transEnd()),
                formatted.offset(raw.romaStart()), formatted.offset(raw.romaEnd()),
                raw.readingSpans(), raw.smallRunHoldsWords(), raw.showSyllableRomanization()), mapped, base);
    }

    private static final class UniformLineSpacingSpan implements LineHeightSpan {
        private final Spacing spacing;
        private int baseDescent;
        private int lastWritten = Integer.MIN_VALUE;
        private int previousEnd = -1;

        UniformLineSpacingSpan(Spacing spacing) {
            this.spacing = spacing;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v,
                Paint.FontMetricsInt fm) {
            if (end >= text.length()) {
                return;
            }
            final boolean paragraphStart = start == 0 || text.charAt(start - 1) == '\n';
            if (paragraphStart) {
                baseDescent = fm.descent;
            }
            final boolean carried = !paragraphStart && start == previousEnd
                    && fm.descent == lastWritten;
            final int from = carried ? baseDescent : Math.max(baseDescent, fm.descent);
            final boolean small = isSmallAt(text, start);
            final boolean nextSmall = isSmallAt(text, end);
            final int clearance;
            if (small) {
                clearance = (nextSmall && !endsWithNewline(text, end))
                        ? spacing.smallClear() : spacing.boundaryClear();
            } else {
                clearance = nextSmall ? spacing.boundaryClear() : spacing.mainClear();
            }
            baseDescent = Math.max(baseDescent, from);
            lastWritten = from + clearance;
            fm.descent = lastWritten;
            if (fm.bottom < fm.descent) {
                fm.bottom = fm.descent;
            }
            previousEnd = end;
        }

        private static boolean endsWithNewline(CharSequence text, int end) {
            return end > 0 && end <= text.length() && text.charAt(end - 1) == '\n';
        }
    }

    private static boolean isSmallAt(CharSequence text, int offset) {
        if (!(text instanceof Spanned spanned)) {
            return false;
        }
        final int window = Math.min(offset + 1, text.length());
        final RelativeSizeSpan[] spans = spanned.getSpans(offset, window, RelativeSizeSpan.class);
        for (RelativeSizeSpan span : spans) {
            final int spanStart = spanned.getSpanStart(span);
            final int spanEnd = spanned.getSpanEnd(span);
            if (spanStart <= offset && offset < spanEnd) {
                return true;
            }
        }
        return false;
    }

    // Check if line is background vocal or secondary lyric
    static boolean isSecondaryLine(@Nullable LyricsLine line) {
        return line != null && (line.isBG() || LyricsPanelView.isSecondaryLyric(line));
    }

    static String displayLineText(LyricsLine line) {
        final String raw = line.text();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (!isSecondaryLine(line)) {
            return raw;
        }
        return stripBgParens(LyricsPanelView.stripParentheses(raw)).trim();
    }

    static String stripBgParens(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != '(' && c != ')' && c != '（' && c != '）') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean isBidiOrInvisible(char c) {
        return switch (c) {
            case '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
                 '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                 '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF' -> true;
            default -> false;
        };
    }

    static String stripBidiControls(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (isBidiOrInvisible(c)) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : s;
    }

    static boolean containsJoiningScript(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            final int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if ((cp >= 0x0600 && cp <= 0x06FF)
                    || (cp >= 0x0750 && cp <= 0x077F)
                    || (cp >= 0x08A0 && cp <= 0x08FF)
                    || (cp >= 0xFB50 && cp <= 0xFDFF)
                    || (cp >= 0xFE70 && cp <= 0xFEFF)
                    || cp == 0x200D || cp == 0x200C) {
                return true;
            }
            final int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reading shown for a line, and where each word's own reading sits inside it.
     */
    record Reading(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    static Reading readingFor(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji,
            @Nullable List<WordTiming> timings) {
        final String orig = displayLineText(line).trim();
        if (orig.isEmpty() || LyricsRomanizer.isPurelyLatinScript(orig)) {
            return new Reading(null, null);
        }
        if (perWordRomaji) {
            final List<Word> words = readingWords(line, index, romanizedLines);
            if (!words.isEmpty() && anyReading(words)) {
                final StringBuilder text = new StringBuilder();
                final List<WordTiming> spans = new ArrayList<>(words.size());
                final String original = displayLineText(line);
                int at = 0;
                String previous = null;
                for (Word word : words) {
                    String reading = word.romaji() != null ? word.romaji() : word.text().trim();
                    if (isSecondaryLine(line)) {
                        reading = stripBgParens(reading);
                    }
                    reading = reading.trim();
                    if (reading.isEmpty()) {
                        continue;
                    }
                    final String own = (isSecondaryLine(line) ? stripBgParens(word.text()) : word.text()).trim();
                    final int found = own.isEmpty() ? -1 : original.indexOf(own, at);
                    //noinspection SizeReplaceableByIsEmpty
                    if (text.length() > 0 && !sameWrittenWord(at, found, previous, own)) {
                        text.append(' ');
                    }
                    final int start = text.length();
                    text.append(reading);
                    spans.add(new WordTiming(start, text.length(), word.startMs(), word.endMs(), null));
                    if (found >= 0) {
                        at = found + own.length();
                        previous = own;
                    }
                }
                if (!spans.isEmpty()) {
                    return new Reading(text.toString(), closeReadingGaps(spans, line));
                }
            }
        }
        return wholeLineReading(line, index, romanizedLines, timings);
    }

    // Build syllable romanization timings for words, skipping Latin words
    @Nullable
    static List<WordTiming> buildSyllableRomanizedTimings(
            LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines,
            boolean perWordRomaji,
            @Nullable List<WordTiming> timings,
            String mainText) {
        if (timings == null || timings.isEmpty() || mainText.isEmpty()) {
            return null;
        }
        if (romanizedLines == null && !perWordRomaji) {
            return null;
        }
        final String cleanMain = stripBidiControls(mainText).trim();
        if (cleanMain.isEmpty() || isRtlText(cleanMain)
                || (ScriptDetector.detect(cleanMain) == ScriptDetector.Script.OTHER
                        && !ScriptDetector.containsCjk(cleanMain))
                || LyricsRomanizer.isPurelyLatinScript(cleanMain)) {
            return null;
        }

        String wholeRoman = lineRomanizationAt(line, index, romanizedLines);
        if (wholeRoman != null && stripBidiControls(wholeRoman).trim().equalsIgnoreCase(cleanMain)) {
            return null;
        }

        final int n = timings.size();
        final String[] ownWords = new String[n];
        final boolean[] isLatinWord = new boolean[n];
        for (int i = 0; i < n; i++) {
            final WordTiming wt = timings.get(i);
            final int s = Math.max(0, Math.min(wt.start(), mainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), mainText.length()));
            final String own = stripBidiControls(mainText.substring(s, e)).trim();
            ownWords[i] = own;
            isLatinWord[i] = LyricsRomanizer.isPurelyLatinScript(own);
        }

        final String[] romTexts = new String[n];
        final boolean[] romTrailingSpaces = new boolean[n];
        boolean populated = false;

        // 1. Check if the original provider line itself already carried valid per-word romaji on every timing.
        boolean hasNativeTimingRomaji = true;
        boolean anyNativeNonEmpty = false;
        for (int i = 0; i < n; i++) {
            final String tr = timings.get(i).romaji();
            if (!isLatinWord[i]) {
                if (tr == null || tr.trim().isEmpty() || ScriptDetector.containsCjk(tr)) {
                    hasNativeTimingRomaji = false;
                    break;
                }
                anyNativeNonEmpty = true;
            }
        }
        if (hasNativeTimingRomaji && anyNativeNonEmpty) {
            for (int i = 0; i < n; i++) {
                if (isLatinWord[i]) {
                    romTexts[i] = "";
                    romTrailingSpaces[i] = false;
                } else {
                    String r = timings.get(i).romaji();
                    r = r != null ? stripBidiControls(isSecondaryLine(line) ? stripBgParens(r) : r) : "";
                    romTrailingSpaces[i] = r.endsWith(" ") || timings.get(i).hasTrailingSpace();
                    romTexts[i] = r.trim();
                }
            }
            populated = true;
        }

        // 2. Align wholeRoman (or reconstructed full romanization) onto timings using phonetic guides + DP.
        if (!populated) {
            if (wholeRoman == null || wholeRoman.trim().isEmpty()) {
                final List<Word> rWords = readingWords(line, index, romanizedLines);
                if (!rWords.isEmpty() && anyReading(rWords)) {
                    final StringBuilder sb = new StringBuilder();
                    for (Word rw : rWords) {
                        String r = rw.romaji();
                        if (r != null && !r.trim().isEmpty() && !ScriptDetector.containsCjk(r)) {
                            if (isSecondaryLine(line)) {
                                r = stripBgParens(r);
                            }
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                                sb.append(' ');
                            }
                            sb.append(r.trim());
                            if (rw.endsWithSpace() || r.endsWith(" ")) {
                                sb.append(' ');
                            }
                        }
                    }
                    final String built = sb.toString().trim();
                    if (!built.isEmpty()) {
                        wholeRoman = built;
                    }
                }
            }

            final List<String> guides = new ArrayList<>(n);
            final List<String> syllables = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final String own = ownWords[i];
                syllables.add(own);
                guides.add(phoneticGuideFor(own, mainText, timings.get(i).end(), isLatinWord[i]));
            }

            if (wholeRoman != null && !wholeRoman.trim().isEmpty()) {
                final List<String> aligned = alignPhoneticRomaji(
                        stripBidiControls(wholeRoman).trim(), guides, syllables, isLatinWord);
                for (int i = 0; i < n; i++) {
                    if (isLatinWord[i]) {
                        romTexts[i] = "";
                        romTrailingSpaces[i] = false;
                        continue;
                    }
                    String chunk = i < aligned.size() && aligned.get(i) != null ? aligned.get(i) : "";
                    final boolean chunkTrailingSpace = chunk.endsWith(" ");
                    chunk = chunk.trim();
                    if (chunk.isEmpty() || ScriptDetector.containsCjk(chunk)
                            || chunk.equalsIgnoreCase(ownWords[i])) {
                        chunk = guides.get(i);
                    }
                    // If the original word has no internal spaces, join internal spaces in its romaji chunk
                    // (e.g. "yeogijeogi seo" -> "yeogijeogiseo", "insa leul" -> "insaleul").
                    if (!ownWords[i].contains(" ") && chunk.contains(" ")
                            && ScriptDetector.contains(ownWords[i], Character.UnicodeScript.HANGUL)) {
                        chunk = chunk.replace(" ", "");
                    }
                    romTexts[i] = chunk;
                    romTrailingSpaces[i] = chunkTrailingSpace;
                }
                populated = true;
            } else {
                // Fallback to direct phonetic guides if wholeRoman is absent
                boolean anyGuide = false;
                for (int i = 0; i < n; i++) {
                    if (isLatinWord[i]) {
                        romTexts[i] = "";
                    } else {
                        final String g = guides.get(i);
                        romTexts[i] = (g != null && !g.equalsIgnoreCase(ownWords[i]) && !ScriptDetector.containsCjk(g))
                                ? g.trim() : "";
                        if (!romTexts[i].isEmpty()) {
                            anyGuide = true;
                        }
                    }
                }
                populated = anyGuide;
            }
        }

        if (!populated) {
            return null;
        }

        boolean anyRoman = false;
        final List<WordTiming> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final WordTiming wt = timings.get(i);
            final int s = Math.max(0, Math.min(wt.start(), mainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), mainText.length()));
            final String own = ownWords[i];

            String r = (isLatinWord[i] || romTexts[i] == null) ? "" : romTexts[i].trim();
            if (!r.isEmpty() && (r.equalsIgnoreCase(own) || ScriptDetector.containsCjk(r))) {
                r = "";
            }

            final boolean mainHasTrailingSpace = wt.hasTrailingSpace()
                    || (e < mainText.length() && Character.isWhitespace(mainText.charAt(e)))
                    || (e > s && Character.isWhitespace(mainText.charAt(e - 1)))
                    || (i + 1 < n && timings.get(i + 1).start() > e
                            && mainText.substring(e, Math.min(mainText.length(), timings.get(i + 1).start())).indexOf(' ') >= 0);

            final boolean trailSpace = !r.isEmpty() && (mainHasTrailingSpace || romTrailingSpaces[i]);
            if (!r.isEmpty()) {
                anyRoman = true;
                r = r + (trailSpace ? " " : "");
            }
            out.add(new WordTiming(wt.start(), wt.end(), wt.startMs(), wt.endMs(), r, trailSpace));
        }

        return anyRoman ? out : null;
    }

    /**
     * Computes a Latin phonetic guide for a single word/syllable so DP alignment never compares
     * Latin candidates against non-Latin characters.
     */
    private static String phoneticGuideFor(
            String ownWord,
            String fullLineText,
            int wordEndOffset,
            boolean isLatin) {
        if (ownWord == null || ownWord.isEmpty()) {
            return "";
        }
        if (isLatin) {
            return ownWord;
        }
        if (ScriptDetector.contains(ownWord, Character.UnicodeScript.HANGUL)) {
            // Include next Hangul character for liaison if single-char syllable inside a Korean word
            if (ownWord.length() == 1 && wordEndOffset < fullLineText.length()) {
                final char nextC = fullLineText.charAt(wordEndOffset);
                if (nextC >= 0xAC00 && nextC <= 0xD7A3) {
                    final String twoChar = HangulRomanizer.romanize(ownWord + nextC);
                    final String nextOnly = HangulRomanizer.romanize(String.valueOf(nextC));
                    if (twoChar.length() > nextOnly.length() && twoChar.endsWith(nextOnly)) {
                        return twoChar.substring(0, twoChar.length() - nextOnly.length()).replace("-", "");
                    }
                }
            }
            return HangulRomanizer.romanize(ownWord).replace("-", "").trim();
        }
        if (ScriptDetector.containsCjk(ownWord)) {
            final LocalRomanizer.Result local = LocalRomanizer.romanize(Collections.singletonList(ownWord));
            if (local != null && !local.lines.isEmpty()) {
                final String r = local.lines.get(0);
                if (r != null && !r.isEmpty() && !ScriptDetector.containsCjk(r)) {
                    return r.trim();
                }
            }
            if (ScriptDetector.containsKana(ownWord)) {
                return KanaRomanizer.romanize(ownWord).trim();
            }
            return "";
        }
        final ScriptDetector.Script script = ScriptDetector.detect(ownWord);
        if (script != null) {
            return switch (script) {
                case CYRILLIC -> CyrillicRomanizer.romanize(ownWord).trim();
                case GREEK -> GreekRomanizer.romanize(ownWord).trim();
                case GEORGIAN -> GeorgianRomanizer.romanize(ownWord).trim();
                case ARMENIAN -> ArmenianRomanizer.romanize(ownWord).trim();
                default -> "";
            };
        }
        return "";
    }

    // Dynamic programming alignment between phonetic guides and full romanized string
    private static List<String> alignPhoneticRomaji(
            String fullRoman,
            List<String> guides,
            List<String> syllables,
            boolean[] isLatinWord) {
        final int N = fullRoman.length();
        final int M = guides.size();
        if (M == 0) {
            return Collections.emptyList();
        }
        if (N == 0) {
            return new ArrayList<>(Collections.nCopies(M, ""));
        }

        boolean anyGuide = false;
        for (String g : guides) {
            if (g != null && !g.trim().isEmpty()) {
                anyGuide = true;
                break;
            }
        }
        if (!anyGuide) {
            final String[] split = fullRoman.trim().split("\\s+");
            final List<String> chunks = new ArrayList<>(M);
            for (int i = 0; i < M; i++) {
                final int tokStart = (i * split.length) / M;
                final int tokEnd = (i == M - 1) ? split.length : (((i + 1) * split.length) / M);
                final StringBuilder sb = new StringBuilder();
                for (int k = tokStart; k < tokEnd && k < split.length; k++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(split[k]);
                }
                if (i < M - 1 && sb.length() > 0) sb.append(' ');
                chunks.add(sb.toString());
            }
            return chunks;
        }

        final double[][] dp = new double[M + 1][N + 1];
        final int[][] path = new int[M + 1][N + 1];
        for (int i = 0; i <= M; i++) {
            Arrays.fill(dp[i], Double.MAX_VALUE);
            Arrays.fill(path[i], -1);
        }
        dp[0][0] = 0.0;

        for (int i = 1; i <= M; i++) {
            final String guideRom = guides.get(i - 1) != null ? guides.get(i - 1) : "";
            final boolean isLatin = isLatinWord[i - 1];
            final String origSyl = syllables.get(i - 1);
            final int guideLen = Math.max(1, guideRom.length());

            final int minLen = isLatin ? Math.max(1, guideLen - 1) : 1;
            final int maxLen = isLatin
                    ? guideLen + 4
                    : Math.max(guideLen * 3 + 5, Math.max(origSyl.length() * 5, 16));

            for (int j = 1; j <= N; j++) {
                double bestCost = Double.MAX_VALUE;
                int bestPrevJ = -1;

                for (int len = minLen; len <= maxLen; len++) {
                    final int k = j - len;
                    if (k < 0) {
                        break;
                    }
                    if (dp[i - 1][k] >= Double.MAX_VALUE * 0.5) {
                        continue;
                    }
                    final String candidate = fullRoman.substring(k, j);
                    if (candidate.charAt(0) == ' ' && candidate.trim().length() > 0) {
                        continue;
                    }
                    final double cost = dp[i - 1][k] + phoneticMatchCost(candidate, guideRom, isLatin);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestPrevJ = k;
                    }
                }
                dp[i][j] = bestCost;
                path[i][j] = bestPrevJ;
            }
        }

        final List<String> results = new ArrayList<>(Collections.nCopies(M, ""));
        int currJ = N;
        if (dp[M][N] >= Double.MAX_VALUE * 0.5) {
            double minEndCost = Double.MAX_VALUE;
            for (int k = N; k >= 0; k--) {
                if (dp[M][k] < minEndCost) {
                    minEndCost = dp[M][k];
                    currJ = k;
                }
            }
        }

        for (int i = M; i > 0; i--) {
            final int prevJ = path[i][currJ];
            if (prevJ < 0) {
                results.set(i - 1, "");
            } else {
                results.set(i - 1, fullRoman.substring(prevJ, currJ));
                currJ = prevJ;
            }
        }

        if (currJ > 0 && !results.isEmpty()) {
            results.set(0, fullRoman.substring(0, currJ) + results.get(0));
        }
        int totalLen = 0;
        for (String r : results) {
            totalLen += r.length();
        }
        if (totalLen < N && !results.isEmpty()) {
            final int safeStart = Math.min(N, Math.max(0, totalLen));
            results.set(M - 1, results.get(M - 1) + fullRoman.substring(safeStart));
        }
        return results;
    }

    private static double phoneticMatchCost(String candidate, String guide, boolean isLatin) {
        final String cTrim = candidate.trim();
        final String gTrim = guide.trim();
        if (cTrim.isEmpty() && !gTrim.isEmpty()) {
            return 50.0;
        }
        if (isLatin) {
            final int dist = levenshtein(cTrim.toLowerCase(Locale.ROOT), gTrim.toLowerCase(Locale.ROOT));
            return dist * 50.0 + Math.abs(cTrim.length() - gTrim.length()) * 0.5;
        }
        if (gTrim.isEmpty()) {
            return cTrim.length() * 1.2 + (candidate.endsWith(" ") ? -0.3 : 0.0);
        }
        final String cCompact = compactPhonetic(cTrim, false);
        final String gCompact = compactPhonetic(gTrim, false);
        final int rawDist = levenshtein(cCompact, gCompact);
        final int phonDist = levenshtein(compactPhonetic(cTrim, true), compactPhonetic(gTrim, true));
        final double dist = Math.min((double) rawDist, phonDist + 0.25);
        final int lenDiff = Math.abs(cCompact.length() - gCompact.length());
        final double spaceBonus = candidate.endsWith(" ") ? -0.35 : 0.0;
        return dist * 2.0 + lenDiff * 0.8 + spaceBonus;
    }

    private static String compactPhonetic(String s, boolean foldConsonants) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c == ' ' || c == '-' || c == '\'') {
                continue;
            }
            if (foldConsonants) {
                c = switch (c) {
                    case 'r' -> 'l';
                    case 'p' -> 'b';
                    case 'k' -> 'g';
                    case 't' -> 'd';
                    default -> c;
                };
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int levenshtein(String s1, String s2) {
        final int len1 = s1.length();
        final int len2 = s2.length();
        if (len1 == 0) return len2;
        if (len2 == 0) return len1;
        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];
        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            final char c1 = s1.charAt(i - 1);
            for (int j = 1; j <= len2; j++) {
                final int cost = (c1 == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            final int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[len2];
    }

    private static Reading wholeLineReading(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, @Nullable List<WordTiming> timings) {
        final String whole = lineRomanizationAt(line, index, romanizedLines);
        if (whole == null || timings == null || timings.isEmpty()) {
            return new Reading(whole, null);
        }
        return new Reading(whole, spreadOverWords(whole, displayLineText(line), timings));
    }

    private static boolean anyReading(List<Word> words) {
        for (Word word : words) {
            final String romaji = word.romaji();
            if (romaji != null && !romaji.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameWrittenWord(int from, int start,
            @Nullable String previous, String current) {
        if (previous == null || previous.isEmpty() || current.isEmpty() || start != from) {
            return false;
        }
        return !spellsSyllable(previous) && !spellsSyllable(current);
    }

    private static boolean spellsSyllable(String piece) {
        return !piece.isEmpty() && (isCjkAt(piece, 0) || isCjkAt(piece, piece.length() - 1));
    }

    private static boolean isCjkAt(String text, int index) {
        final char c = text.charAt(index);
        if (Character.isHighSurrogate(c) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1))) {
            return isCjkCodePoint(Character.toCodePoint(c, text.charAt(index + 1)));
        }
        return isCjkCodePoint(c);
    }

    private static List<Word> readingWords(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines != null && index < romanizedLines.size()) {
            final List<Word> words = romanizedLines.get(index).words();
            if (!words.isEmpty()) {
                return words;
            }
        }
        return line.words();
    }

    private static List<WordTiming> closeReadingGaps(List<WordTiming> spans, LyricsLine line) {
        final List<Word> words = line.words();
        final List<WordTiming> closed = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            final WordTiming span = spans.get(i);
            long end = i + 1 < spans.size() ? spans.get(i + 1).startMs() : -1L;
            if (end < 0 && !words.isEmpty()) {
                end = words.get(words.size() - 1).endMs();
            }
            closed.add(end > span.endMs()
                    ? new WordTiming(span.start(), span.end(), span.startMs(), end,
                            span.romaji(), span.hasTrailingSpace())
                    : span);
        }
        return closed;
    }

    static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index,
            boolean splitWordlessLine) {
        final LyricsLine line = lines.get(index);
        final String lineText = displayLineText(line);
        if (line.hasWordTimings() && countWords(lineText) == 1) {
            long rangeStart = line.startTimeMs();
            if (rangeStart < 0 && line.hasWords()) {
                rangeStart = line.words().get(0).startMs();
            }
            long rangeEnd = line.endTimeMs();
            if (rangeEnd <= rangeStart) {
                rangeEnd = index + 1 < lines.size()
                        ? lines.get(index + 1).startTimeMs()
                        : LyricsLine.NO_TIME;
            }
            if (rangeEnd <= rangeStart && line.hasWords()) {
                rangeEnd = line.words().get(line.words().size() - 1).endMs();
            }
            if (rangeStart >= 0 && rangeEnd > rangeStart) {
                final List<WordTiming> synthesized =
                        synthesizeLineChars(lineText, rangeStart, rangeEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        } else if (splitWordlessLine && !line.hasWords()) {
            final long lineStart = line.startTimeMs();
            final long lineEnd = line.endTimeMs() > lineStart ? line.endTimeMs()
                    : index + 1 < lines.size() ? lines.get(index + 1).startTimeMs()
                            : LyricsLine.NO_TIME;
            if (lineStart >= 0 && lineEnd > lineStart) {
                final List<WordTiming> synthesized =
                        synthesizeLineWords(lineText, lineStart, lineEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        }
        return computeWordTimingsFromWords(line);
    }

    private static List<WordTiming> synthesizeLineWords(String text, long startMs, long endMs) {
        final int[] bounds = wordBoundaries(text);
        final List<WordTiming> timings = new ArrayList<>();
        int words = 0;
        for (int i = 0; i + 1 < bounds.length; i++) {
            if (isWord(text, bounds[i], bounds[i + 1])) {
                words++;
            }
        }
        if (words == 0) {
            return timings;
        }
        final long span = endMs - startMs;
        int seen = 0;
        for (int i = 0; i + 1 < bounds.length && seen < words; i++) {
            if (!isWord(text, bounds[i], bounds[i + 1])) {
                continue;
            }
            final long wordStart = startMs + span * seen / words;
            final long wordEnd = startMs + span * (seen + 1) / words;
            final boolean trailSpace = bounds[i + 1] < text.length()
                    && Character.isWhitespace(text.charAt(bounds[i + 1]));
            timings.add(new WordTiming(bounds[i], bounds[i + 1], wordStart,
                    Math.max(wordEnd, wordStart + 1), null, trailSpace));
            seen++;
        }
        return timings;
    }

    private static boolean isWord(String text, int start, int end) {
        if (end <= start) {
            return false;
        }
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int[] wordBoundaries(String text) {
        final List<Integer> bounds = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            final int charCount = Character.charCount(codePoint);
            if (isCjkCodePoint(codePoint)) {
                if (runStart >= 0) {
                    bounds.add(runStart);
                    bounds.add(i);
                    runStart = -1;
                }
                bounds.add(i);
                bounds.add(i + charCount);
            } else if (Character.isWhitespace(codePoint)) {
                if (runStart >= 0) {
                    bounds.add(runStart);
                    bounds.add(i);
                    runStart = -1;
                }
            } else if (runStart < 0) {
                runStart = i;
            }
            i += charCount;
        }
        if (runStart >= 0) {
            bounds.add(runStart);
            bounds.add(text.length());
        }
        final int[] result = new int[bounds.size()];
        for (int i = 0; i < bounds.size(); i++) {
            result[i] = bounds.get(i);
        }
        return result;
    }

    private static List<WordTiming> synthesizeLineChars(String text, long startMs, long endMs) {
        final int[] boundaries = clusterBoundaries(text);
        final int clusterCount = boundaries.length - 1;
        if (clusterCount < 2 || endMs <= startMs) {
            return Collections.emptyList();
        }
        final List<WordTiming> timings = new ArrayList<>(clusterCount);
        for (int i = 0; i < clusterCount; i++) {
            final long clusterStart = startMs + (endMs - startMs) * i / clusterCount;
            final long clusterEnd = i + 1 == clusterCount
                    ? endMs
                    : startMs + (endMs - startMs) * (i + 1) / clusterCount;
            timings.add(new WordTiming(boundaries[i], boundaries[i + 1], clusterStart, clusterEnd, null));
        }
        return timings;
    }

    private static int[] clusterBoundaries(String text) {
        final BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        final List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        for (int boundary = iterator.first();
                boundary != BreakIterator.DONE;
                boundary = iterator.next()) {
            if (boundary > 0) {
                bounds.add(boundary);
            }
        }
        final int[] result = new int[bounds.size()];
        for (int i = 0; i < bounds.size(); i++) {
            result[i] = bounds.get(i);
        }
        return result;
    }

    private static int countWords(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                continue;
            }
            if (isCjkCodePoint(codePoint)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                words++;
                continue;
            }
            inWord = true;
        }
        return words + (inWord ? 1 : 0);
    }

    static boolean isCjkCodePoint(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF);
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int fromIndex) {
        final int max = haystack.length() - needle.length();
        final int nLen = needle.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nLen)) {
                return i;
            }
        }
        return -1;
    }

    // Compute word timings from line words and fill gap if any
    private static List<WordTiming> computeWordTimingsFromWords(LyricsLine line) {
        if (!line.hasWordTimings()) {
            return Collections.emptyList();
        }

        final List<WordTiming> timings = new ArrayList<>(line.words().size() + 2);
        final String text = displayLineText(line);
        final int textLength = text.length();
        if (textLength == 0) {
            return Collections.emptyList();
        }

        int offset = 0;
        for (Word word : line.words()) {
            String rawWordText = isSecondaryLine(line) ? stripBgParens(word.text()) : word.text();
            if (rawWordText == null || rawWordText.isEmpty()) {
                continue;
            }
            final String trimmed = rawWordText.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final boolean wordTrailingSpace = word.endsWithSpace() || rawWordText.endsWith(" ");
            String rawRom = word.romaji();
            if (rawRom != null && isSecondaryLine(line)) {
                rawRom = stripBgParens(rawRom);
            }

    // If word token contain internal space split it into pieces
            if (trimmed.indexOf(' ') >= 0) {
                final String[] parts = trimmed.split("\\s+");
                final String[] romParts = (rawRom != null && !rawRom.trim().isEmpty())
                        ? rawRom.trim().split("\\s+") : null;
                int totalChars = 0;
                for (String p : parts) {
                    totalChars += p.length();
                }
                totalChars = Math.max(1, totalChars);
                final long totalDur = Math.max(1L, word.endMs() - word.startMs());
                long curStartMs = word.startMs();

                for (int pi = 0; pi < parts.length; pi++) {
                    final String part = parts[pi];
                    if (part.isEmpty()) {
                        continue;
                    }
                    final boolean isLast = (pi == parts.length - 1);
                    final long partDur = (totalDur * part.length()) / totalChars;
                    final long partEndMs = isLast
                            ? Math.max(curStartMs + 1L, word.endMs())
                            : curStartMs + Math.max(1L, partDur);
                    final String partRom = (romParts != null && romParts.length == parts.length)
                            ? romParts[pi] : null;
                    offset = appendMatchedWordTiming(
                            timings, text, textLength, offset, part,
                            curStartMs, partEndMs, partRom, !isLast || wordTrailingSpace);
                    curStartMs = partEndMs;
                }
            } else {
                offset = appendMatchedWordTiming(
                        timings, text, textLength, offset, trimmed,
                        word.startMs(), word.endMs(), rawRom, wordTrailingSpace);
            }
        }

        // Fill any remaining untimed text at the end of the line
        if (offset < textLength && !timings.isEmpty()) {
            fillUntimedGap(timings, text, textLength, offset, textLength,
                    timings.get(timings.size() - 1).endMs(),
                    Math.max(line.endTimeMs(), timings.get(timings.size() - 1).endMs() + 200L));
        }

        return timings;
    }

    private static int appendMatchedWordTiming(
            List<WordTiming> timings,
            String text,
            int textLength,
            int offset,
            String token,
            long startMs,
            long endMs,
            @Nullable String rom,
            boolean wordTrailingSpace) {
        int start = text.indexOf(token, offset);
        if (start < 0) {
            start = indexOfIgnoreCase(text, token, offset);
        }
        if (start < 0) {
            // Do not advance offset blindly when a token fails to match!
            return offset;
        }
        if (start > offset) {
            final long gapStartMs = timings.isEmpty()
                    ? Math.max(0L, startMs - 150L)
                    : timings.get(timings.size() - 1).endMs();
            fillUntimedGap(timings, text, textLength, offset, start, gapStartMs, startMs);
        }
        final int end = Math.min(start + token.length(), textLength);
        if (start >= end) {
            return offset;
        }
        final boolean trailSpace = wordTrailingSpace
                || (end < textLength && Character.isWhitespace(text.charAt(end)));
        timings.add(new WordTiming(start, end, startMs, Math.max(endMs, startMs + 1L), rom, trailSpace));
        return end;
    }

    private static void fillUntimedGap(
            List<WordTiming> timings,
            String text,
            int textLength,
            int gapStart,
            int gapEnd,
            long startMs,
            long endMs) {
        final String gap = text.substring(gapStart, gapEnd);
        if (gap.trim().isEmpty()) {
            if (!timings.isEmpty() && gap.indexOf(' ') >= 0) {
                final int lastIdx = timings.size() - 1;
                final WordTiming prev = timings.get(lastIdx);
                if (!prev.hasTrailingSpace()) {
                    timings.set(lastIdx, new WordTiming(
                            prev.start(), prev.end(), prev.startMs(), prev.endMs(), prev.romaji(), true));
                }
            }
            return;
        }

        // Collect non-whitespace tokens inside the untimed gap
        final List<int[]> spans = new ArrayList<>(2);
        int i = gapStart;
        while (i < gapEnd) {
            while (i < gapEnd && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            final int s = i;
            while (i < gapEnd && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i > s) {
                spans.add(new int[] {s, i});
            }
        }
        if (spans.isEmpty()) {
            return;
        }

        final long safeStart = Math.max(0L, startMs);
        final long safeEnd = Math.max(safeStart + spans.size(), endMs);
        final long dur = safeEnd - safeStart;
        for (int idx = 0; idx < spans.size(); idx++) {
            final int[] sp = spans.get(idx);
            final String tok = text.substring(sp[0], sp[1]);
            // Attach pure punctuation to the preceding WordTiming if adjacent
            if (!timings.isEmpty() && isPurePunctuation(tok)) {
                final int lastIdx = timings.size() - 1;
                final WordTiming prev = timings.get(lastIdx);
                if (prev.end() == sp[0]) {
                    final boolean trail = sp[1] < textLength && Character.isWhitespace(text.charAt(sp[1]));
                    timings.set(lastIdx, new WordTiming(
                            prev.start(), sp[1], prev.startMs(), prev.endMs(), prev.romaji(), trail));
                    continue;
                }
            }
            final long wStart = safeStart + (dur * idx) / spans.size();
            final long wEnd = safeStart + (dur * (idx + 1)) / spans.size();
            final boolean trail = sp[1] < textLength && Character.isWhitespace(text.charAt(sp[1]));
            timings.add(new WordTiming(sp[0], sp[1], wStart, Math.max(wEnd, wStart + 1L), null, trail));
        }
    }

    private static boolean isPurePunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // Build display text for line with romanization and translation layout
    static BuildResult buildLineText(LyricsLine line, List<WordTiming> timings, int index,
            boolean perWordRomaji, List<LyricsLine> romanizedLines, List<String> translatedLines,
            LyricsPanelView.OnlyMode onlyMode, Paint basePaint, boolean isRightAligned) {
        final Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
        basePaint.getFontMetricsInt(baseFm);
        String original = displayLineText(line);

        final int lineHeight = baseFm.descent - baseFm.ascent;
        final int mainClear = Math.max(0, (int) (lineHeight * MAIN_LINE_SPACING + 0.5f) - lineHeight);
        final int smallClear = Math.max(0, (int) (lineHeight * TRANSLATION_RELATIVE_SIZE * 0.18f + 0.5f));
        final int boundaryClear = Math.max(Dim.dp4, mainClear + Dim.dp(2));
        final Spacing spacing = new Spacing(mainClear, smallClear, boundaryClear);

        String translation = translationAt(line, index, translatedLines);

        if (onlyMode == LyricsPanelView.OnlyMode.TRANS) {
            final String content = translation != null ? translation : original;
            SpannableString text = new SpannableString(content);
            final int transStart = content.isEmpty() ? -1 : 0;
            final int transEnd = content.isEmpty() ? -1 : content.length();
            attachUniformSpacing(text, spacing);
            if (!content.isEmpty()) {
                text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtlText(content), isRightAligned)),
                        0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return new BuildResult(text, null, transStart, transEnd, -1, -1);
        }

        if (onlyMode == LyricsPanelView.OnlyMode.ROMA) {
            final Romanized roma = romanizedWithTimings(line, index, romanizedLines, timings);
            final boolean readsNothing = roma.text() == null;
            final String content = readsNothing ? original : roma.text();
            SpannableString text = new SpannableString(content);
            attachUniformSpacing(text, spacing);
            if (content.isEmpty()) {
                return new BuildResult(text, null, -1, -1, -1, -1);
            }
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtlText(content), isRightAligned)),
                    0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final List<WordTiming> spans = readsNothing ? timings : roma.spans();
            return new BuildResult(text, null, -1, -1, -1, -1, spans);
        }

        if (onlyMode == LyricsPanelView.OnlyMode.BOTH) {
            final DualLine mainAndSmall =
                    dualLineFor(line, timings, index, translation, original, romanizedLines);
            SpannableString dual = new SpannableString(mainAndSmall.text());
            attachUniformSpacing(dual, spacing);
            final int smallStart = mainAndSmall.smallStart();
            final int smallEnd = mainAndSmall.smallEnd();
            final boolean smallIsRoma = mainAndSmall.smallIsRoma();
            if (smallStart >= 0) {
                applySpans(dual, Collections.emptyList(), 0, smallStart - 1,
                        smallIsRoma ? -1 : smallStart, smallIsRoma ? -1 : smallEnd,
                        smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                        isRightAligned);
            } else {
                boolean isRtl = isRtlText(dual);
                dual.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtl, isRightAligned)),
                        0, dual.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            final List<WordTiming> dualSpans = mainAndSmall.wordSpans();
            return new BuildResult(dual, null,
                    smallIsRoma ? -1 : smallStart,
                    smallIsRoma ? -1 : smallEnd,
                    smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                    dualSpans, mainAndSmall.wordsOnSmall());
        }

        // Check if 1:1 per-syllable romanization (attached directly under Kanji/words) is active.
        final List<WordTiming> syllableRomanTimings = Settings.LYRICS_WORD_SYNC.get()
                ? buildSyllableRomanizedTimings(line, index, romanizedLines, perWordRomaji, timings, original)
                : null;
        final boolean showSyllableRomanization = syllableRomanTimings != null && !syllableRomanTimings.isEmpty();

        final Reading reading = showSyllableRomanization
                ? new Reading(null, syllableRomanTimings)
                : readingFor(line, index, romanizedLines, perWordRomaji, timings);
        final String romanization = showSyllableRomanization ? null : reading.text();

        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        StringBuilder builder = new StringBuilder();
        int romaStart = -1;
        int romaEnd = -1;
        int transStart = -1;
        int transEnd = -1;

        final int originalStart = 0;
        builder.append(original);
        final int originalEnd = builder.length();

        if (swap) {
            if (translation != null) {
                builder.append('\n');
                transStart = builder.length();
                builder.append(translation);
                transEnd = builder.length();
            }
            if (romanization != null) {
                builder.append('\n');
                romaStart = builder.length();
                builder.append(romanization);
                romaEnd = builder.length();
            }
        } else {
            if (romanization != null) {
                builder.append('\n');
                romaStart = builder.length();
                builder.append(romanization);
                romaEnd = builder.length();
            }
            if (translation != null) {
                builder.append('\n');
                transStart = builder.length();
                builder.append(translation);
                transEnd = builder.length();
            }
        }

        SpannableString text = new SpannableString(builder.toString());
        attachUniformSpacing(text, spacing);
        final List<WordTiming> effectiveTimings = showSyllableRomanization ? syllableRomanTimings : timings;
        ForegroundColorSpan unsungSpan = applySpans(text, effectiveTimings, originalStart, originalEnd,
                romaStart, romaEnd, transStart, transEnd, isRightAligned);
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                reading.spans(), false, showSyllableRomanization);
    }

    private static void attachUniformSpacing(SpannableString text, Spacing spacing) {
        if (text.length() <= 0) {
            return;
        }
        text.setSpan(new UniformLineSpacingSpan(spacing), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    record DualLine(String text, boolean mainIsRoma, boolean smallIsRoma, int smallStart, int smallEnd,
            @Nullable List<WordTiming> wordSpans, boolean wordsOnSmall) {
    }

    @NonNull
    static DualLine dualLineFor(LyricsLine line, @Nullable List<WordTiming> lineTimings, int index,
            @Nullable String translation, String original,
            @Nullable List<LyricsLine> romanizedLines) {
        final Romanized roma = romanizedWithTimings(line, index, romanizedLines, lineTimings);
        final String romanization = roma.text();
        final boolean mainIsRoma;
        final String main;
        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        final String small;
        if (translation != null && romanization != null) {
            mainIsRoma = !swap;
            main = mainIsRoma ? romanization : translation;
            small = mainIsRoma ? translation : romanization;
        } else if (translation != null) {
            mainIsRoma = false;
            main = swap ? translation : original;
            small = swap ? original : translation;
        } else if (romanization != null) {
            mainIsRoma = true;
            main = romanization;
            small = null;
        } else {
            mainIsRoma = false;
            main = original;
            small = null;
        }
        final List<WordTiming> words = romanization != null ? roma.spans() : lineTimings;
        final boolean wordsOnSmall = romanization != null ? !mainIsRoma : swap;
        if (small == null) {
            return new DualLine(main, mainIsRoma, false, -1, -1, words, false);
        }
        final int smallStart = main.length() + 1;
        return new DualLine(main + "\n" + small, mainIsRoma,
                romanization != null && !mainIsRoma, smallStart,
                smallStart + small.length(),
                shiftSpans(words, wordsOnSmall ? smallStart : 0), wordsOnSmall);
    }

    private static List<WordTiming> shiftSpans(@Nullable List<WordTiming> spans, int by) {
        if (spans == null || spans.isEmpty() || by == 0) {
            return spans;
        }
        final List<WordTiming> shifted = new ArrayList<>(spans.size());
        for (WordTiming span : spans) {
            shifted.add(new WordTiming(span.start() + by, span.end() + by,
                    span.startMs(), span.endMs(), span.romaji(), span.hasTrailingSpace()));
        }
        return shifted;
    }

    @Nullable
    static String translationAt(LyricsLine line, int index,
            @Nullable List<String> translatedLines) {
        if (translatedLines == null || index >= translatedLines.size()) {
            return null;
        }
        String raw = translatedLines.get(index);
        if (raw == null) {
            return null;
        }
        if (isSecondaryLine(line)) {
            raw = stripBgParens(LyricsPanelView.stripParentheses(raw));
            if (index > 0) {
                final String prevRaw = translatedLines.get(index - 1);
                if (prevRaw != null && prevRaw.trim().equalsIgnoreCase(raw.trim())) {
                    return null;
                }
            }
        }
        final String trimmed = raw.trim();
        final String origTrimmed = displayLineText(line).trim();
        return !trimmed.isEmpty() && !trimmed.equalsIgnoreCase(origTrimmed)
                ? trimmed : null;
    }

    @Nullable
    static String lineRomanizationAt(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines == null || index >= romanizedLines.size()) {
            return null;
        }
        final String origTrimmed = displayLineText(line).trim();
        if (origTrimmed.isEmpty() || LyricsRomanizer.isPurelyLatinScript(origTrimmed)) {
            return null;
        }
        String roma = romanizedLines.get(index).text();
        if (roma == null) {
            return null;
        }
        if (isSecondaryLine(line)) {
            roma = stripBgParens(LyricsPanelView.stripParentheses(roma));
            if (index > 0) {
                final String prevRoma = romanizedLines.get(index - 1).text();
                if (prevRoma != null && prevRoma.trim().equalsIgnoreCase(roma.trim())
                        && prevRoma.trim().length() > origTrimmed.length() * 2 + 4) {
                    return null;
                }
            }
        }
        roma = roma.trim();
        return !roma.isEmpty() && !roma.equalsIgnoreCase(origTrimmed) ? roma : null;
    }

    private record Romanized(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    private static Romanized romanizedWithTimings(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines,
            @Nullable List<WordTiming> lineTimings) {
        final Reading reading = readingFor(line, index, romanizedLines, true, lineTimings);
        return new Romanized(reading.text(), reading.spans());
    }

    @Nullable
    private static List<WordTiming> spreadOverWords(String whole, String text,
            @Nullable List<WordTiming> lineTimings) {
        if (lineTimings == null || lineTimings.isEmpty()) {
            return null;
        }
        final int[][] tokens = LocalRomanizer.readingTokens(whole);
        if (tokens.length == 0) {
            return null;
        }
        final List<Word> words = wordsAt(text, lineTimings);
        if (words.isEmpty()) {
            return null;
        }
        final int[][] spread = LocalRomanizer.spreadReading(words, whole);
        if (spread == null) {
            return null;
        }
        final List<WordTiming> spans = new ArrayList<>(tokens.length);
        for (int i = 0; i < words.size(); i++) {
            final Word word = words.get(i);
            final int from = spread[i][0];
            final int to = Math.min(spread[i][1], tokens.length);
            if (to <= from) {
                continue;
            }
            spreadWordWindow(spans, tokens, from, to, word.startMs(),
                    windowEndFor(words, i, word.endMs()));
        }
        return spans.isEmpty() ? null : spans;
    }

    private static long windowEndFor(List<Word> words, int index, long wordEndMs) {
        if (index + 1 >= words.size()) {
            return wordEndMs;
        }
        final Word word = words.get(index);
        final long nextStart = words.get(index + 1).startMs();
        if (nextStart > word.startMs() && nextStart != wordEndMs) {
            return nextStart;
        }
        return wordEndMs;
    }

    private static void spreadWordWindow(List<WordTiming> out, int[][] tokens,
            int from, int to, long startMs, long endMs) {
        int spelling = 0;
        for (int at = from; at < to; at++) {
            spelling += tokens[at][1] - tokens[at][0];
        }
        final long window = endMs - startMs;
        if (spelling <= 0 || window <= 0) {
            final long end = Math.max(endMs, startMs + 1);
            for (int at = from; at < to; at++) {
                out.add(new WordTiming(tokens[at][0], tokens[at][1], startMs, end, null));
            }
            return;
        }
        int said = 0;
        long cursor = startMs;
        for (int at = from; at < to; at++) {
            said += tokens[at][1] - tokens[at][0];
            final long share = at + 1 == to
                    ? Math.max(endMs, cursor + 1)
                    : Math.max(startMs + window * said / spelling, cursor + 1);
            out.add(new WordTiming(tokens[at][0], tokens[at][1], cursor, share, null));
            cursor = share;
        }
    }

    private static List<Word> wordsAt(String text, List<WordTiming> lineTimings) {
        final List<Word> words = new ArrayList<>(lineTimings.size());
        for (WordTiming timing : lineTimings) {
            final int start = timing.start();
            final int end = Math.min(timing.end(), text.length());
            if (start < 0 || start >= end) {
                continue;
            }
            words.add(new Word(timing.startMs(), timing.endMs(), text.substring(start, end),
                    timing.romaji(), timing.hasTrailingSpace()));
        }
        return words;
    }

    @Nullable
    static String romanizationForOnlyMode(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        return romanizedWithTimings(line, index, romanizedLines, null).text();
    }

    @Nullable
    static String lineReading(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji) {
        return readingFor(line, index, romanizedLines, perWordRomaji, null).text();
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd,
            boolean isRightAligned) {
        ForegroundColorSpan unsungSpan = null;
        if (romaStart >= 0 && romaEnd > romaStart) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), romaStart, romaEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LyricsColors.secondaryTextColor() | 0xFF000000),
                    romaStart, romaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LyricsColors.secondaryTextColor() | 0xFF000000),
                    transStart, transEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (Settings.LYRICS_WORD_SYNC.get() && !timings.isEmpty()) {
            int unsung = LyricsColors.unsungWordColor() | 0xFF000000;
            unsungSpan = new ForegroundColorSpan(unsung);
            text.setSpan(unsungSpan, originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (originalStart >= 0 && originalEnd > originalStart) {
            int spanEnd = originalEnd < text.length() && text.charAt(originalEnd) == '\n'
                    ? originalEnd + 1 : originalEnd;
            boolean isOrigRtl = isRtlText(text.subSequence(originalStart, originalEnd));
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isOrigRtl, isRightAligned)),
                    originalStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (romaStart >= 0 && romaEnd > romaStart) {
            int spanEnd = romaEnd < text.length() && text.charAt(romaEnd) == '\n'
                    ? romaEnd + 1 : romaEnd;
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(false, isRightAligned)),
                    romaStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart) {
            int spanEnd = transEnd < text.length() && text.charAt(transEnd) == '\n'
                    ? transEnd + 1 : transEnd;
            boolean isTransRtl = isRtlText(text.subSequence(transStart, transEnd));
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isTransRtl, isRightAligned)),
                    transStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return unsungSpan;
    }

    static boolean isRtlText(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        return ScriptDetector.containsRtl(text);
    }

    public static Layout.Alignment resolveAlignment(boolean isRtl, boolean isRightAligned) {
        if (isRightAligned) {
            return isRtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            return isRtl ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        }
    }
}
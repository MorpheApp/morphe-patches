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
import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
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

    /**
     * @param hasTrailingSpace Whether a space follows the word, which a syllable reading keeps so
     *                         the readings of two words do not run together.
     */
    record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji, boolean hasTrailingSpace) {

        /** A reading ending in a space stands for the space after its word. */
        WordTiming(int start, int end, long startMs, long endMs, @Nullable String romaji) {
            this(start, end, startMs, endMs, romaji,
                    romaji != null && romaji.endsWith(" "));
        }
    }

    /**
     * @param showSyllableRomanization The reading is drawn under each syllable by the view, and
     *                                 {@code readingSpans} carry it.
     * @param originalStart            Where the original starts in {@code text}, which the word
     *                                 offsets are measured from. Zero when an only mode drops it.
     */
    record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd,
            @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords,
            boolean showSyllableRomanization, int originalStart) {

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, null);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd,
                @Nullable List<WordTiming> readingSpans) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, readingSpans, false);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd,
                @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                    readingSpans, smallRunHoldsWords, false, 0);
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
     * @param words Word offsets relative to the original's start.
     */
    static PresentedLine present(BuildResult raw, List<WordTiming> words) {
        final int originalStart = raw.originalStart();
        if (!LyricsPresentation.isFormattingEnabled()) {
            return new PresentedLine(raw, words, originalStart);
        }
        LyricsTextFormatter.Result formatted = LyricsPresentation.format(raw.text().toString());
        if (formatted.text().contentEquals(raw.text())) {
            return new PresentedLine(raw, words, originalStart);
        }
        SpannableString text = new SpannableString(formatted.text());
        for (Object span : raw.text().getSpans(0, raw.text().length(), Object.class)) {
            final int start = formatted.offset(raw.text().getSpanStart(span));
            final int end = formatted.offset(raw.text().getSpanEnd(span));
            if (end > start) text.setSpan(span, start, end, raw.text().getSpanFlags(span));
        }
        final int base = formatted.offset(originalStart);
        List<WordTiming> mapped = new ArrayList<>(words.size());
        for (WordTiming word : words) {
            final int start = formatted.offset(word.start() + originalStart) - base;
            final int end = formatted.offset(word.end() + originalStart) - base;
            if (end > start) mapped.add(new WordTiming(start, end, word.startMs(), word.endMs(),
                    word.romaji(), word.hasTrailingSpace()));
        }
        return new PresentedLine(new BuildResult(text, raw.unsungSpan(),
                formatted.offset(raw.transStart()), formatted.offset(raw.transEnd()),
                formatted.offset(raw.romaStart()), formatted.offset(raw.romaEnd()),
                raw.readingSpans(), raw.smallRunHoldsWords(), raw.showSyllableRomanization(), base),
                mapped, base);
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
        RelativeSizeSpan[] spans = spanned.getSpans(offset, window, RelativeSizeSpan.class);
        for (RelativeSizeSpan span : spans) {
            final int spanStart = spanned.getSpanStart(span);
            final int spanEnd = spanned.getSpanEnd(span);
            if (spanStart <= offset && offset < spanEnd) {
                return true;
            }
        }
        return false;
    }

    /** A background vocal, whether the source says so or only writes it in brackets. */
    static boolean isSecondaryLine(LyricsLine line) {
        return line.isBG() || LyricsBracketSplitter.isBracketedVocal(line.text());
    }

    /** The text a line is drawn with: a background vocal sits on a line of its own, so it loses its brackets. */
    static String displayLineText(LyricsLine line) {
        String raw = line.text();
        return isSecondaryLine(line) ? LyricsBracketSplitter.stripAllBrackets(raw).trim() : raw;
    }

    /** Characters that take no room, which a reading laid out against the text must not count. */
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

    /** Arabic script or combining marks, whose letters join up and break apart under letter spacing. */
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
     *
     * <p>Reading it off the words rather than off the stored reading keeps the two the same
     * string, so the highlight can follow the words without having to guess where a reading
     * of its own would land.
     */
    record Reading(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    /**
     * @param timings Null when the caller wants the reading's text alone: they are what a
     *                whole-line reading is spread over.
     */
    static Reading readingFor(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji,
            @Nullable List<WordTiming> timings) {
        String orig = displayLineText(line).trim();
        if (orig.isEmpty() || LyricsRomanizer.isPurelyLatinScript(orig)) {
            return new Reading(null, null);
        }
        if (perWordRomaji) {
            List<Word> words = readingWords(line, index, romanizedLines);
            // A word shown as written stands in for a reading only where the line is really read per word.
            if (!words.isEmpty() && anyReading(words)) {
                StringBuilder text = new StringBuilder();
                List<WordTiming> spans = new ArrayList<>(words.size());
                String original = displayLineText(line);
                // A background vocal is drawn without its brackets, so its readings lose them too.
                final boolean secondary = isSecondaryLine(line);
                int at = 0;
                String previous = null;
                for (Word word : words) {
                    String reading = word.romaji() != null ? word.romaji() : word.text().trim();
                    if (secondary) {
                        reading = LyricsBracketSplitter.stripAllBrackets(reading);
                    }
                    reading = reading.trim();
                    if (reading.isEmpty()) {
                        continue;
                    }
                    // The line's own spacing tells a word handed over in pieces from one written whole; an
                    // unplaced word keeps the gap.
                    String own = (secondary ? LyricsBracketSplitter.stripAllBrackets(word.text()) : word.text()).trim();
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

    /**
     * The line's word timings, each carrying the reading of its own syllable to be drawn under it.
     * Words already in Latin letters read as written and carry none.
     *
     * @return Null when the line has no reading to split up this way.
     */
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
        String cleanMain = stripBidiControls(mainText).trim();
        if (cleanMain.isEmpty() || ScriptDetector.containsRtl(cleanMain)
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
        String[] ownWords = new String[n];
        boolean[] isLatinWord = new boolean[n];
        for (int i = 0; i < n; i++) {
            WordTiming wt = timings.get(i);
            final int s = Math.max(0, Math.min(wt.start(), mainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), mainText.length()));
            String own = stripBidiControls(mainText.substring(s, e)).trim();
            ownWords[i] = own;
            isLatinWord[i] = LyricsRomanizer.isPurelyLatinScript(own);
        }

        String[] romTexts = new String[n];
        boolean[] romTrailingSpaces = new boolean[n];
        boolean populated = false;

        // A provider that reads every word itself has already said which reading goes where.
        boolean hasNativeTimingRomaji = true;
        boolean anyNativeNonEmpty = false;
        for (int i = 0; i < n; i++) {
            String tr = timings.get(i).romaji();
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
                    r = r != null ? stripBidiControls(isSecondaryLine(line) ? LyricsBracketSplitter.stripAllBrackets(r) : r) : "";
                    romTrailingSpaces[i] = r.endsWith(" ") || timings.get(i).hasTrailingSpace();
                    romTexts[i] = r.trim();
                }
            }
            populated = true;
        }

        // Otherwise the line's whole reading, or one joined up from its words, is cut into pieces that
        // best match a reading worked out locally for each syllable.
        if (!populated) {
            if (wholeRoman == null || wholeRoman.trim().isEmpty()) {
                List<Word> rWords = readingWords(line, index, romanizedLines);
                if (!rWords.isEmpty() && anyReading(rWords)) {
                    StringBuilder sb = new StringBuilder();
                    for (Word rw : rWords) {
                        String r = rw.romaji();
                        if (r != null && !r.trim().isEmpty() && !ScriptDetector.containsCjk(r)) {
                            if (isSecondaryLine(line)) {
                                r = LyricsBracketSplitter.stripAllBrackets(r);
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
                    String built = sb.toString().trim();
                    if (!built.isEmpty()) {
                        wholeRoman = built;
                    }
                }
            }

            List<String> guides = new ArrayList<>(n);
            List<String> syllables = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String own = ownWords[i];
                syllables.add(own);
                guides.add(phoneticGuideFor(own, mainText, timings.get(i).end(), isLatinWord[i]));
            }

            if (wholeRoman != null && !wholeRoman.trim().isEmpty()) {
                List<String> aligned = alignPhoneticRomaji(
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
                    // A Korean word written without a space reads as one word too:
                    // "yeogijeogi seo" becomes "yeogijeogiseo".
                    if (!ownWords[i].contains(" ") && chunk.contains(" ")
                            && ScriptDetector.contains(ownWords[i], Character.UnicodeScript.HANGUL)) {
                        chunk = chunk.replace(" ", "");
                    }
                    romTexts[i] = chunk;
                    romTrailingSpaces[i] = chunkTrailingSpace;
                }
                populated = true;
            } else {
                // No reading to cut up, so the local one is all there is.
                boolean anyGuide = false;
                for (int i = 0; i < n; i++) {
                    if (isLatinWord[i]) {
                        romTexts[i] = "";
                    } else {
                        String g = guides.get(i);
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
        List<WordTiming> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            WordTiming wt = timings.get(i);
            final int s = Math.max(0, Math.min(wt.start(), mainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), mainText.length()));
            String own = ownWords[i];

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
     * A local reading of one syllable, so the alignment compares Latin letters with Latin letters
     * and never with the script being read.
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
            // A lone Hangul syllable is read with the one after it, which can change how it ends.
            if (ownWord.length() == 1 && wordEndOffset < fullLineText.length()) {
                final char nextC = fullLineText.charAt(wordEndOffset);
                if (nextC >= 0xAC00 && nextC <= 0xD7A3) {
                    String twoChar = HangulRomanizer.romanize(ownWord + nextC);
                    String nextOnly = HangulRomanizer.romanize(String.valueOf(nextC));
                    if (twoChar.length() > nextOnly.length() && twoChar.endsWith(nextOnly)) {
                        return twoChar.substring(0, twoChar.length() - nextOnly.length()).replace("-", "");
                    }
                }
            }
            return HangulRomanizer.romanize(ownWord).replace("-", "").trim();
        }
        if (ScriptDetector.containsCjk(ownWord)) {
            LocalRomanizer.Result local = LocalRomanizer.romanize(Collections.singletonList(ownWord));
            if (local != null && !local.lines.isEmpty()) {
                String r = local.lines.get(0);
                if (r != null && !r.isEmpty() && !ScriptDetector.containsCjk(r)) {
                    return r.trim();
                }
            }
            if (ScriptDetector.containsKana(ownWord)) {
                return KanaRomanizer.romanize(ownWord).trim();
            }
            return "";
        }
        ScriptDetector.Script script = ScriptDetector.detect(ownWord);
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

    /**
     * Cuts a whole-line reading into one piece per syllable, choosing the cuts that make each piece
     * closest to that syllable's local reading.
     */
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
            String[] split = fullRoman.trim().split("\\s+");
            List<String> chunks = new ArrayList<>(M);
            for (int i = 0; i < M; i++) {
                final int tokStart = (i * split.length) / M;
                final int tokEnd = (i == M - 1) ? split.length : (((i + 1) * split.length) / M);
                StringBuilder sb = new StringBuilder();
                for (int k = tokStart; k < tokEnd && k < split.length; k++) {
                    //noinspection SizeReplaceableByIsEmpty
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(split[k]);
                }
                //noinspection SizeReplaceableByIsEmpty
                if (i < M - 1 && sb.length() > 0) sb.append(' ');
                chunks.add(sb.toString());
            }
            return chunks;
        }

        double[][] dp = new double[M + 1][N + 1];
        int[][] path = new int[M + 1][N + 1];
        for (int i = 0; i <= M; i++) {
            Arrays.fill(dp[i], Double.MAX_VALUE);
            Arrays.fill(path[i], -1);
        }
        dp[0][0] = 0.0;

        for (int i = 1; i <= M; i++) {
            String guideRom = guides.get(i - 1) != null ? guides.get(i - 1) : "";
            final boolean isLatin = isLatinWord[i - 1];
            String origSyl = syllables.get(i - 1);
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
                    String candidate = fullRoman.substring(k, j);
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

        List<String> results = new ArrayList<>(Collections.nCopies(M, ""));
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
        String cTrim = candidate.trim();
        String gTrim = guide.trim();
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
        String cCompact = compactPhonetic(cTrim, false);
        String gCompact = compactPhonetic(gTrim, false);
        final int rawDist = levenshtein(cCompact, gCompact);
        final int phonDist = levenshtein(compactPhonetic(cTrim, true), compactPhonetic(gTrim, true));
        final double dist = Math.min((double) rawDist, phonDist + 0.25);
        final int lenDiff = Math.abs(cCompact.length() - gCompact.length());
        final double spaceBonus = candidate.endsWith(" ") ? -0.35 : 0.0;
        return dist * 2.0 + lenDiff * 0.8 + spaceBonus;
    }

    private static String compactPhonetic(String s, boolean foldConsonants) {
        StringBuilder sb = new StringBuilder(s.length());
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
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[len2];
    }

    /**
     * The stored string stays the one drawn, so the words are measured against it and not
     * against the original.
     */
    private static Reading wholeLineReading(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, @Nullable List<WordTiming> timings) {
        String whole = lineRomanizationAt(line, index, romanizedLines);
        if (whole == null || timings == null || timings.isEmpty()) {
            return new Reading(whole, null);
        }
        return new Reading(whole, spreadOverWords(whole, displayLineText(line), timings));
    }

    private static boolean anyReading(List<Word> words) {
        for (Word word : words) {
            String romaji = word.romaji();
            if (romaji != null && !romaji.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether two written pieces are one word, which is the case only when the line has them side by
     * side. Han, Hangul and kana keep their gap either way, being a syllable each; letters are the
     * word they spell and must not be read apart.
     */
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

    /** An aligned reading rides in the romanization's own lines; the lyric's own words are read
     * only when they already carry one, as a provider's ruby does. */
    private static List<Word> readingWords(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines != null && index < romanizedLines.size()) {
            List<Word> words = romanizedLines.get(index).words();
            if (!words.isEmpty()) {
                return words;
            }
        }
        return line.words();
    }

    private static List<WordTiming> closeReadingGaps(List<WordTiming> spans, LyricsLine line) {
        List<Word> words = line.words();
        List<WordTiming> closed = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            WordTiming span = spans.get(i);
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

    /**
     * A one-word line is swept by character across its word's span, but only when it has one: a
     * line timed only as a whole lights in one go like every other such line in the song.
     */
    private static boolean hasTimedWord(LyricsLine line) {
        List<Word> words = line.words();
        return words.size() > 1
                || (words.size() == 1 && words.get(0).endMs() > words.get(0).startMs());
    }

    static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index,
            boolean splitWordlessLine) {
        LyricsLine line = lines.get(index);
        String lineText = displayLineText(line);
        if (countWords(lineText) == 1 && hasTimedWord(line)) {
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
                List<WordTiming> synthesized =
                        synthesizeLineChars(lineText, rangeStart, rangeEnd);
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
                        synthesizeLineWords(lineText, lineStart, lineEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        }
        return computeWordTimingsFromWords(line);
    }

    /**
     * Spreads a line's own span across its words.
     *
     * <p>A Han, Hangul or kana character is said as a syllable of its own and so counts as a word
     * on its own, the same way {@link #countWords} counts it. Letters are said as the word they
     * spell, so they are taken a run at a time.
     */
    private static List<WordTiming> synthesizeLineWords(String text, long startMs, long endMs) {
        int[] bounds = wordBoundaries(text);
        List<WordTiming> timings = new ArrayList<>();
        // A run and the character after it share a boundary, and the gaps between words are
        // spelled by the same list, so only the spans that hold text are words.
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

    /** Whether a span between two boundaries carries anything that is said. */
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

    /** Where each word of a line begins and ends, counted the way it is said. */
    private static int[] wordBoundaries(String text) {
        List<Integer> bounds = new ArrayList<>();
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
        int[] result = new int[bounds.size()];
        for (int i = 0; i < bounds.size(); i++) {
            result[i] = bounds.get(i);
        }
        return result;
    }

    private static List<WordTiming> synthesizeLineChars(String text, long startMs, long endMs) {
        int[] boundaries = clusterBoundaries(text);
        final int clusterCount = boundaries.length - 1;
        if (clusterCount < 2 || endMs <= startMs) {
            return Collections.emptyList();
        }
        List<WordTiming> timings = new ArrayList<>(clusterCount);
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
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        for (int boundary = iterator.first();
                boundary != BreakIterator.DONE;
                boundary = iterator.next()) {
            if (boundary > 0) {
                bounds.add(boundary);
            }
        }
        int[] result = new int[bounds.size()];
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
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
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

    /** The line's own word timings, with any text they leave out lit along with its neighbours. */
    private static List<WordTiming> computeWordTimingsFromWords(LyricsLine line) {
        if (!line.hasWordTimings()) {
            return Collections.emptyList();
        }

        List<WordTiming> timings = new ArrayList<>(line.words().size() + 2);
        String text = displayLineText(line);
        final int textLength = text.length();
        if (textLength == 0) {
            return Collections.emptyList();
        }

        // The drawn text of a background vocal has lost its brackets, so its words lose them too.
        final boolean secondary = isSecondaryLine(line);
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
                final long totalDur = Math.max(1L, word.endMs() - word.startMs());
                long curStartMs = word.startMs();

                for (int pi = 0; pi < parts.length; pi++) {
                    String part = parts[pi];
                    if (part.isEmpty()) {
                        continue;
                    }
                    final boolean isLast = (pi == parts.length - 1);
                    final long partDur = (totalDur * part.length()) / totalChars;
                    final long partEndMs = isLast
                            ? Math.max(curStartMs + 1L, word.endMs())
                            : curStartMs + Math.max(1L, partDur);
                    String partRom = (romParts != null && romParts.length == parts.length)
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

        // Text after the last timed word, such as closing punctuation, is lit after it.
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
            // Left where it was, so the words after an unmatched one can still be found.
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
        String gap = text.substring(gapStart, gapEnd);
        if (gap.trim().isEmpty()) {
            if (!timings.isEmpty() && gap.indexOf(' ') >= 0) {
                final int lastIdx = timings.size() - 1;
                WordTiming prev = timings.get(lastIdx);
                if (!prev.hasTrailingSpace()) {
                    timings.set(lastIdx, new WordTiming(
                            prev.start(), prev.end(), prev.startMs(), prev.endMs(), prev.romaji(), true));
                }
            }
            return;
        }

        // Collect non-whitespace tokens inside the untimed gap
        List<int[]> spans = new ArrayList<>(2);
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
            int[] sp = spans.get(idx);
            String tok = text.substring(sp[0], sp[1]);
            // Attach pure punctuation to the preceding WordTiming if adjacent
            if (!timings.isEmpty() && isPurePunctuation(tok)) {
                final int lastIdx = timings.size() - 1;
                WordTiming prev = timings.get(lastIdx);
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

    /**
     * Builds the displayed text for a line, adding the translation and reading (when shown) in a
     * smaller, dimmer style and coloring each word sung or unsung for the karaoke highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     */
    static BuildResult buildLineText(LyricsLine line, List<WordTiming> timings, int index,
            boolean perWordRomaji, List<LyricsLine> romanizedLines, List<String> translatedLines,
            LyricsPanelView.OnlyMode onlyMode, Paint basePaint, boolean isRightAligned) {
        Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
        basePaint.getFontMetricsInt(baseFm);
        String original = displayLineText(line);

        final int lineHeight = baseFm.descent - baseFm.ascent;
        final int mainClear = Math.max(0, (int) (lineHeight * MAIN_LINE_SPACING + 0.5f) - lineHeight);
        final int smallClear = Math.max(0, (int) (lineHeight * TRANSLATION_RELATIVE_SIZE * 0.18f + 0.5f));
        final int boundaryClear = Math.max(Dim.dp4, mainClear + Dim.dp(2));
        Spacing spacing = new Spacing(mainClear, smallClear, boundaryClear);

        String translation = translationAt(line, index, translatedLines);

        // An only mode never drops a line: what the provider left untranslated keeps the
        // original rather than leaving a hole in the song.
        if (onlyMode == LyricsPanelView.OnlyMode.TRANS) {
            String content = translation != null ? translation : original;
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
            Romanized roma = romanizedWithTimings(line, index, romanizedLines, timings);
            final boolean readsNothing = roma.text() == null;
            String content = readsNothing ? original : roma.text();
            SpannableString text = new SpannableString(content);
            attachUniformSpacing(text, spacing);
            if (content.isEmpty()) {
                return new BuildResult(text, null, -1, -1, -1, -1);
            }
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtlText(content), isRightAligned)),
                    0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // No bounds reported paints the whole line the lyric colour, and none of the reading's fade.
            List<WordTiming> spans = readsNothing ? timings : roma.spans();
            return new BuildResult(text, null, -1, -1, -1, -1, spans);
        }

        // Both at once: the original goes and whichever side carries more of the line takes
        // the main slot. Decided per line, so a line left untranslated keeps its translation
        // rather than going thin.
        if (onlyMode == LyricsPanelView.OnlyMode.BOTH) {
            DualLine mainAndSmall =
                    dualLineFor(line, timings, index, translation, original, romanizedLines);
            SpannableString dual = new SpannableString(mainAndSmall.text());
            attachUniformSpacing(dual, spacing);
            final int smallStart = mainAndSmall.smallStart();
            final int smallEnd = mainAndSmall.smallEnd();
            // The small run is the side that is not the main one, and the reading's when it landed there.
            final boolean smallIsRoma = mainAndSmall.smallIsRoma();
            if (smallStart >= 0) {
                // Without this the small run keeps the main size and colour, so the two lines
                // are told apart only by where they sit.
                applySpans(dual, Collections.emptyList(), 0, smallStart - 1,
                        smallIsRoma ? -1 : smallStart, smallIsRoma ? -1 : smallEnd,
                        smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                        isRightAligned);
            } else {
                boolean isRtl = isRtlText(dual);
                dual.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtl, isRightAligned)),
                        0, dual.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // A small run carrying the word timings is what is sung, and keeps the reading's
            // colour, which is what tells it apart: the lyric's colour would leave the two lines
            // looking alike. A reading that never arrived leaves the words on the lyric itself,
            // and a lyric in its own colour is what makes them light up.
            List<WordTiming> dualSpans = mainAndSmall.wordSpans();
            return new BuildResult(dual, null,
                    smallIsRoma ? -1 : smallStart,
                    smallIsRoma ? -1 : smallEnd,
                    smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                    dualSpans, mainAndSmall.wordsOnSmall());
        }

        // A reading drawn over each word sits where the word's own highlight is painted, so the
        // two land on top of each other. With word sync on, the view draws each syllable's reading
        // under it instead; otherwise the reading goes on a line of its own, and takes its progress
        // from the same words rather than from a second set of marks.
        List<WordTiming> syllableRomanTimings = Settings.LYRICS_WORD_SYNC.get()
                ? buildSyllableRomanizedTimings(line, index, romanizedLines, perWordRomaji, timings, original)
                : null;
        final boolean showSyllableRomanization = syllableRomanTimings != null;
        Reading reading = showSyllableRomanization
                ? new Reading(null, syllableRomanTimings)
                : readingFor(line, index, romanizedLines, perWordRomaji, timings);
        String romanization = reading.text();

        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        StringBuilder builder = new StringBuilder();
        int romaStart = -1;
        int romaEnd = -1;
        int transStart = -1;
        int transEnd = -1;
        // The swap puts the translation above the lyric and the reading below it.
        String above = swap ? translation : romanization;
        if (above != null) {
            builder.append(above);
            builder.append('\n');
            if (swap) {
                transStart = 0;
                transEnd = builder.length();
            } else {
                romaStart = 0;
                romaEnd = builder.length();
            }
        }
        final int originalStart = builder.length();
        builder.append(original);
        final int originalEnd = builder.length();
        String below = swap ? romanization : translation;
        if (below != null) {
            builder.append('\n');
            if (swap) {
                romaStart = builder.length();
                builder.append(below);
                romaEnd = builder.length();
            } else {
                transStart = builder.length();
                builder.append(below);
                transEnd = builder.length();
            }
        }

        SpannableString text = new SpannableString(builder.toString());
        attachUniformSpacing(text, spacing);
        ForegroundColorSpan unsungSpan = applySpans(text,
                showSyllableRomanization ? syllableRomanTimings : timings,
                originalStart, originalEnd, romaStart, romaEnd, transStart, transEnd, isRightAligned);
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                reading.spans(), false, showSyllableRomanization, originalStart);
    }

    private static void attachUniformSpacing(SpannableString text, Spacing spacing) {
        if (text.length() <= 0) {
            return;
        }
        text.setSpan(new UniformLineSpacingSpan(spacing), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * One line of the dual mode: the text to show and where the small run sits in it. The copy
     * button reads the same call, so the two cannot disagree about which side is the main line.
     *
     * @param smallStart Where the small run starts, -1 when the line carries only one side.
     * @param wordSpans Null unless something shown here is spoken word by word; when it is not,
     *                  its offsets already measure against {@code text}.
     */
    record DualLine(String text, boolean mainIsRoma, boolean smallIsRoma, int smallStart, int smallEnd,
            @Nullable List<WordTiming> wordSpans, boolean wordsOnSmall) {
    }

    /** Both only-flags standing, so the original goes and the two swap-aware sides take over. */
    @NonNull
    static DualLine dualLineFor(LyricsLine line, @Nullable List<WordTiming> lineTimings, int index,
            @Nullable String translation, String original,
            @Nullable List<LyricsLine> romanizedLines) {
        Romanized roma = romanizedWithTimings(line, index, romanizedLines, lineTimings);
        String romanization = roma.text();
        final boolean mainIsRoma;
        String main;
        // The main slot is what the reading would have held, so a line whose reading never arrived keeps
        // the original rather than a hole, and the translation, spoken as a whole and lighting nothing,
        // steps down to the small run instead of taking a line it could never light.
        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        String small;
        if (translation != null && romanization != null) {
            mainIsRoma = !swap;
            main = mainIsRoma ? romanization : translation;
            small = mainIsRoma ? translation : romanization;
        } else if (translation != null) {
            // The original takes the reading's slot when the reading never arrived, so the swap
            // still decides which of the two that is: off puts the original above the translation,
            // on puts it below. Swapping this way hands the main slot to the translation, which is
            // what lights the line, so the words move down with the original they belong to.
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
        // The words follow the reading, and fall back to the original when there is none; the
        // translation never holds them.
        List<WordTiming> words = romanization != null ? roma.spans() : lineTimings;
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
        List<WordTiming> shifted = new ArrayList<>(spans.size());
        for (WordTiming span : spans) {
            shifted.add(new WordTiming(span.start() + by, span.end() + by,
                    span.startMs(), span.endMs(), span.romaji(), span.hasTrailingSpace()));
        }
        return shifted;
    }

    /** The line's own translation, null when it has none or it only repeats the original. */
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
            raw = LyricsBracketSplitter.stripAllBrackets(raw);
            // The lead's translation carried down onto its background vocal would only repeat it.
            if (index > 0) {
                String prevRaw = translatedLines.get(index - 1);
                if (prevRaw != null && prevRaw.trim().equalsIgnoreCase(raw.trim())) {
                    return null;
                }
            }
        }
        String trimmed = raw.trim();
        String origTrimmed = displayLineText(line).trim();
        return !trimmed.isEmpty() && !trimmed.equalsIgnoreCase(origTrimmed)
                ? trimmed : null;
    }

    /** The line's own romanization, without falling back to the per-word one. */
    @Nullable
    static String lineRomanizationAt(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines == null || index >= romanizedLines.size()) {
            return null;
        }
        String origTrimmed = displayLineText(line).trim();
        if (origTrimmed.isEmpty() || LyricsRomanizer.isPurelyLatinScript(origTrimmed)) {
            return null;
        }
        String roma = romanizedLines.get(index).text();
        if (roma == null) {
            return null;
        }
        if (isSecondaryLine(line)) {
            roma = LyricsBracketSplitter.stripAllBrackets(roma);
            // The lead's reading carried down onto a far shorter background vocal is not its own.
            if (index > 0) {
                String prevRoma = romanizedLines.get(index - 1).text();
                if (prevRoma != null && prevRoma.trim().equalsIgnoreCase(roma.trim())
                        && prevRoma.trim().length() > origTrimmed.length() * 2 + 4) {
                    return null;
                }
            }
        }
        roma = roma.trim();
        return !roma.isEmpty() && !roma.equalsIgnoreCase(origTrimmed) ? roma : null;
    }

    /** Text and words come back as one, so no word is lit at an offset from another string. */
    private record Romanized(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    private static Romanized romanizedWithTimings(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines,
            @Nullable List<WordTiming> lineTimings) {
        // The provider's own per-word reading comes first: it has already said which syllable
        // belongs to which word, and only a reading left whole is spread by syllable count.
        Reading reading = readingFor(line, index, romanizedLines, true, lineTimings);
        return new Romanized(reading.text(), reading.spans());
    }

    /**
     * The words a whole-line reading is said with, measured against that reading. Needed where the
     * source left the reading whole: the line is then drawn word by word and nothing on it can be lit.
     * The timings are the karaoke pass's own, per character where the source gave none, so the
     * reading lands one syllable at a time.
     */
    @Nullable
    private static List<WordTiming> spreadOverWords(String whole, String text,
            @Nullable List<WordTiming> lineTimings) {
        if (lineTimings == null || lineTimings.isEmpty()) {
            return null;
        }
        int[][] tokens = LocalRomanizer.readingTokens(whole);
        if (tokens.length == 0) {
            return null;
        }
        List<Word> words = wordsAt(text, lineTimings);
        if (words.isEmpty()) {
            return null;
        }
        int[][] spread = LocalRomanizer.spreadReading(words, whole);
        if (spread == null) {
            return null;
        }
        List<WordTiming> spans = new ArrayList<>(tokens.length);
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
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

    /**
     * Runs to the next word's start: a silence is swept rather than stalled on, and two words
     * whose timings overlap cannot light out of order.
     */
    private static long windowEndFor(List<Word> words, int index, long wordEndMs) {
        if (index + 1 >= words.size()) {
            return wordEndMs;
        }
        Word word = words.get(index);
        final long nextStart = words.get(index + 1).startMs();
        if (nextStart > word.startMs() && nextStart != wordEndMs) {
            return nextStart;
        }
        return wordEndMs;
    }

    /**
     * Divided by how much each syllable spells: one window for all of them lights them at once,
     * and the lit edge, being the furthest, jumps to the word's last syllable.
     */
    private static void spreadWordWindow(List<WordTiming> out, int[][] tokens,
            int from, int to, long startMs, long endMs) {
        int spelling = 0;
        for (int at = from; at < to; at++) {
            spelling += tokens[at][1] - tokens[at][0];
        }
        final long window = endMs - startMs;
        if (spelling <= 0 || window <= 0) {
            // Nothing to divide by, so the syllables share what there is.
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

    /** One word per timing, which is all the placement needs to know about the line. */
    private static List<Word> wordsAt(String text, List<WordTiming> lineTimings) {
        List<Word> words = new ArrayList<>(lineTimings.size());
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

    /** What a romanization-only line shows: its own, else the per-word one joined up. */
    @Nullable
    static String romanizationForOnlyMode(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        return romanizedWithTimings(line, index, romanizedLines, null).text();
    }

    /**
     * The reading a line shows, laid out beside the line rather than over its words.
     *
     * <p>Only a lyric the user asked to be romanized reports per-word readings, so joining
     * those up cannot put a reading on a line the user never asked for one on.
     */
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
        return ScriptDetector.isLineRtl(text);
    }

    public static Layout.Alignment resolveAlignment(boolean isRtl, boolean isRightAligned) {
        if (isRightAligned) {
            return isRtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            return isRtl ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        }
    }
}
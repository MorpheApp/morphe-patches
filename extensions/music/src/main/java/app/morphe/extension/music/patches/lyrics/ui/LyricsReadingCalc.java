/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;

/**
 * The reading a line is drawn with: the whole-line romanization, the provider's per-word one, and
 * the ruby of its words. Works on plain lyrics models, so it stays free of any view or span.
 */
final class LyricsReadingCalc {

    private LyricsReadingCalc() {
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
        String orig = LyricsTextUtils.displayLineText(line).trim();
        if (orig.isEmpty() || LyricsRomanizer.isPurelyLatinScript(orig)) {
            return new Reading(null, null);
        }
        if (perWordRomaji) {
            List<Word> words = readingWords(line, index, romanizedLines);
            // A word shown as written stands in for a reading only where the line is really read per word.
            if (!words.isEmpty() && anyReading(words)) {
                StringBuilder text = new StringBuilder();
                List<WordTiming> spans = new ArrayList<>(words.size());
                String original = LyricsTextUtils.displayLineText(line);
                // A background vocal is drawn without its brackets, so its readings lose them too.
                final boolean secondary = LyricsTextUtils.isSecondaryLine(line);
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
                    spans.add(new WordTiming(start, text.length(), word.startMs(), word.endMs(),
                            null, false, null, timings != null && isEntirelyAveraged(timings)));
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
    @SuppressWarnings("DataFlowIssue")
    static List<WordTiming> buildSyllableRomanizedTimings(
            LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines,
            boolean perWordRomaji,
            @Nullable List<WordTiming> timings,
            String mainText,
            boolean embeddedReading) {
        if (timings == null || timings.isEmpty() || mainText.isEmpty()) {
            return null;
        }
        final boolean wantRoman = romanizedLines != null || perWordRomaji;
        final boolean lineHasRuby = hasAnyRuby(timings);
        if (!lineHasRuby && isEntirelyAveraged(timings) && !Settings.LYRICS_WORD_SYNC.get()) {
            return null;
        }
        if (!wantRoman && !lineHasRuby) {
            return null;
        }
        String cleanMain = LyricsTextUtils.stripBidiControls(mainText).trim();
        if (!lineHasRuby) {
            if (cleanMain.isEmpty() || ScriptDetector.containsRtl(cleanMain)
                    || (ScriptDetector.detect(cleanMain) == ScriptDetector.Script.OTHER
                    && !ScriptDetector.containsCjk(cleanMain))
                    || LyricsRomanizer.isPurelyLatinScript(cleanMain)) {
                return null;
            }
        } else if (cleanMain.isEmpty() || ScriptDetector.containsRtl(cleanMain)) {
            return null;
        }

        String wholeRoman = wantRoman ? lineRomanizationAt(line, index, romanizedLines) : null;
        if (!lineHasRuby && wholeRoman != null && LyricsTextUtils.stripBidiControls(wholeRoman).trim().equalsIgnoreCase(cleanMain)) {
            return null;
        }

        final int n = timings.size();
        String[] ownWords = new String[n];
        boolean[] isLatinWord = new boolean[n];
        for (int i = 0; i < n; i++) {
            WordTiming wt = timings.get(i);
            final int s = Math.max(0, Math.min(wt.start(), mainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), mainText.length()));
            String own = LyricsTextUtils.stripBidiControls(mainText.substring(s, e)).trim();
            ownWords[i] = own;
            isLatinWord[i] = LyricsRomanizer.isPurelyLatinScript(own);
        }

        // Pre-compute ruby per word so ruby==original can be filtered before alignment.
        String[] ownRuby = new String[n];
        boolean[] rubyIsOriginal = new boolean[n];
        for (int i = 0; i < n; i++) {
            String ruby = timings.get(i).ruby();
            ownRuby[i] = ruby;
            rubyIsOriginal[i] = ruby != null && !ruby.isEmpty()
                    && ruby.equalsIgnoreCase(ownWords[i]);
        }

        String[] romTexts = new String[n];
        boolean[] romTrailingSpaces = new boolean[n];
        boolean populated = false;

        final boolean allowTransliteration = Settings.LYRICS_ALLOW_TRANSLITERATION.get();
        boolean hasNativeTimingRomaji = wantRoman;
        boolean anyNativeNonEmpty = false;
        if (wantRoman) {
            for (int i = 0; i < n; i++) {
                if (isLatinWord[i]) {
                    continue;
                }
                String tr = timings.get(i).romaji();
                if (tr != null && !tr.trim().isEmpty()
                        && (allowTransliteration || !ScriptDetector.containsCjk(tr))) {
                    anyNativeNonEmpty = true;
                } else {
                    hasNativeTimingRomaji = false;
                }
            }
        }
        if (wantRoman && anyNativeNonEmpty && (hasNativeTimingRomaji || embeddedReading)) {
            for (int i = 0; i < n; i++) {
                if (isLatinWord[i]) {
                    romTexts[i] = "";
                    romTrailingSpaces[i] = false;
                } else {
                    String r = timings.get(i).romaji();
                    r = r != null ? LyricsTextUtils.stripBidiControls(LyricsTextUtils.isSecondaryLine(line) ? LyricsBracketSplitter.stripAllBrackets(r) : r) : "";
                    final String trimmed = r.trim();
                    // A source that reads only some words must not have the rest read for it.
                    if (trimmed.isEmpty() || (!allowTransliteration && ScriptDetector.containsCjk(trimmed))
                            || trimmed.equalsIgnoreCase(ownWords[i])) {
                        romTexts[i] = "";
                        romTrailingSpaces[i] = false;
                    } else {
                        romTrailingSpaces[i] = r.endsWith(" ") || timings.get(i).hasTrailingSpace();
                        romTexts[i] = trimmed;
                    }
                }
            }
            populated = true;
        }

        // Otherwise the line's whole reading, or one joined up from its words, is cut into pieces that
        // best match a reading worked out locally for each syllable.
        if (wantRoman && !populated) {
            if (wholeRoman == null || wholeRoman.trim().isEmpty()) {
                List<Word> rWords = readingWords(line, index, romanizedLines);
                if (!rWords.isEmpty() && anyReading(rWords)) {
                    StringBuilder sb = new StringBuilder();
                    for (Word rw : rWords) {
                        String r = rw.romaji();
                        // Skip romaji that equals the original, as it is not a reading.
                        if (r != null && !r.trim().isEmpty() && !r.trim().equalsIgnoreCase(rw.text().trim())
                                && (allowTransliteration || !ScriptDetector.containsCjk(r))) {
                            if (LyricsTextUtils.isSecondaryLine(line)) {
                                r = LyricsBracketSplitter.stripAllBrackets(r);
                            }
                            //noinspection SizeReplaceableByIsEmpty
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
            for (int i = 0; i < n; i++) {
                // Skip ruby that equals the original, as the guide would misalign allocateReading.
                guides.add(rubyIsOriginal[i] ? "" : phoneticGuideFor(ownWords[i], mainText, isLatinWord[i]));
            }

            // Han in wholeRoman is original text, not a reading; replace with spaces.
            if (wholeRoman != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < wholeRoman.length(); i++) {
                    sb.append(ScriptDetector.isHan(wholeRoman.charAt(i)) ? ' ' : wholeRoman.charAt(i));
                }
                String filtered = sb.toString().trim();
                wholeRoman = filtered.isEmpty() ? null : filtered;
            }

            if (wholeRoman != null && !wholeRoman.trim().isEmpty()) {
                final String readingKey = readingKey(wholeRoman);
                String[] aligned = LocalRomanizer.allocateReading(
                        LyricsTextUtils.stripBidiControls(wholeRoman).trim(), n, guides,
                        LocalRomanizer.hasCjkWord(ownWords));
                for (int i = 0; i < n; i++) {
                    if (isLatinWord[i]) {
                        romTexts[i] = "";
                        romTrailingSpaces[i] = false;
                        continue;
                    }
                    String chunk = i < aligned.length && aligned[i] != null ? aligned[i] : "";
                    final boolean chunkTrailingSpace = chunk.endsWith(" ");
                    chunk = chunk.trim();
                    final boolean hole = chunk.isEmpty();
                    if (hole || (!allowTransliteration && ScriptDetector.containsCjk(chunk))
                            || chunk.equalsIgnoreCase(ownWords[i])) {
                        final String guide = guides.get(i);
                        // A hole stays a hole when the line's reading already says this syllable somewhere:
                        // the guide is then a tail of a word the allocator placed in another slot.
                        chunk = embeddedReading || (hole && readingKey.contains(readingKey(guide)))
                                ? "" : guide;
                    }
                    // Korean without spaces reads as one word
                    if (!ownWords[i].contains(" ") && chunk.contains(" ")
                            && ScriptDetector.contains(ownWords[i], Character.UnicodeScript.HANGUL)) {
                        chunk = chunk.replace(" ", "");
                    }
                    romTexts[i] = chunk;
                    romTrailingSpaces[i] = chunkTrailingSpace;
                }
                // Single-token reading can't wrap; spread over syllables so the line can
                // break instead of running off the right edge.
                if (!embeddedReading && n > 1 && countFilledChunks(aligned) <= 1) {
                    spreadSingleReading(LyricsTextUtils.stripBidiControls(wholeRoman).trim(),
                            romTexts, romTrailingSpaces, isLatinWord, guides, ownWords);
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
                        romTexts[i] = (g != null && !g.equalsIgnoreCase(ownWords[i]) && (allowTransliteration || !ScriptDetector.containsCjk(g)))
                                ? g.trim() : "";
                        if (!romTexts[i].isEmpty()) {
                            anyGuide = true;
                        }
                    }
                }
                populated = anyGuide;
            }
        }

        if (!populated && !lineHasRuby) {
            return null;
        }

        boolean anyRoman = false;
        boolean anyRuby = false;
        List<WordTiming> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            WordTiming wt = timings.get(i);
            final int s = Math.max(0, Math.min(wt.start(), mainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), mainText.length()));
            String own = ownWords[i];

            String r = (isLatinWord[i] || romTexts[i] == null) ? "" : romTexts[i].trim();
            if (!r.isEmpty() && (r.equalsIgnoreCase(own) || (!allowTransliteration && ScriptDetector.containsCjk(r)))) {
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
            final String ruby = lineHasRuby && ownRuby[i] != null && !ownRuby[i].isEmpty()
                    ? ownRuby[i] : null;
            // Skip ruby that repeats the word as written, since the original already shows it.
            final String drawnRuby = ruby != null && !ruby.equalsIgnoreCase(own) ? ruby : null;
            if (drawnRuby != null) {
                anyRuby = true;
            }
            out.add(new WordTiming(wt.start(), wt.end(), wt.startMs(), wt.endMs(),
                    r.isEmpty() ? null : r, trailSpace, drawnRuby, wt.averaged()));
        }

        return (anyRoman || anyRuby) ? out : null;
    }

    static int countFilledChunks(String[] chunks) {
        int filled = 0;
        for (String chunk : chunks) {
            if (chunk != null && !chunk.trim().isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** Spreads a reading the tokenizer couldn't split over its syllables so it can wrap. */
    static void spreadSingleReading(
            String reading, String[] romTexts, boolean[] romTrailingSpaces,
            boolean[] isLatinWord, List<String> guides, String[] ownWords) {
        final int n = romTexts.length;
        if (reading.isEmpty() || n < 2) {
            return;
        }
        int readingSlots = 0;
        for (int i = 0; i < n; i++) {
            if (!isLatinWord[i] && hasReadableText(ownWords[i])) {
                readingSlots++;
            }
        }
        if (readingSlots < 2) {
            return;
        }

        // Each syllable's own local reading splits the line the way it is said, when known.
        boolean allGuided = true;
        for (int i = 0; i < n; i++) {
            if (isLatinWord[i] || !hasReadableText(ownWords[i])) {
                continue;
            }
            final String guide = guides.get(i);
            if (guide == null || guide.trim().isEmpty() || guide.equalsIgnoreCase(ownWords[i])) {
                allGuided = false;
                break;
            }
        }
        if (allGuided) {
            for (int i = 0; i < n; i++) {
                romTexts[i] = isLatinWord[i] ? "" : guides.get(i).trim();
                romTrailingSpaces[i] = false;
            }
            return;
        }

        // Shorter reading = one syllable's, leave where the allocator put it
        if (reading.codePointCount(0, reading.length()) <= readingSlots) {
            return;
        }

        // Otherwise cut it by how much of the line each syllable is written with.
        final int[] weights = new int[n];
        int totalWeight = 0;
        for (int i = 0; i < n; i++) {
            if (isLatinWord[i] || !hasReadableText(ownWords[i])) {
                continue;
            }
            final int weight = ownWords[i].codePointCount(0, ownWords[i].length());
            weights[i] = weight;
            totalWeight += weight;
        }
        if (totalWeight <= 0) {
            return;
        }
        final int codePoints = reading.codePointCount(0, reading.length());
        int previous = 0;
        int seenWeight = 0;
        int lastSlot = -1;
        for (int i = 0; i < n; i++) {
            if (isLatinWord[i] || weights[i] <= 0) {
                romTexts[i] = "";
                romTrailingSpaces[i] = false;
                continue;
            }
            lastSlot = i;
            seenWeight += weights[i];
            final int cutCodePoint = Math.min(codePoints,
                    Math.max(0, Math.round((float) codePoints * seenWeight / totalWeight)));
            final int cut = reading.offsetByCodePoints(0, cutCodePoint);
            romTexts[i] = reading.substring(previous, cut).trim();
            romTrailingSpaces[i] = false;
            previous = Math.max(previous, cut);
        }
        if (lastSlot >= 0 && previous < reading.length()) {
            romTexts[lastSlot] = (romTexts[lastSlot] + reading.substring(previous)).trim();
        }
    }

    /** Normalized so a guide can be matched against a reading without spacing, case or punctuation. */
    static String readingKey(@Nullable String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** Whether a slot is written with something a reading can belong to, not just punctuation. */
    static boolean hasReadableText(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean isEntirelyAveraged(List<WordTiming> timings) {
        for (WordTiming timing : timings) {
            if (!timing.averaged()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A local reading of one syllable, so the alignment compares Latin letters with Latin letters
     * and never with the script being read.
     */
    static String phoneticGuideFor(String ownWord, String fullLineText, boolean isLatin) {
        if (ownWord == null || ownWord.isEmpty()) {
            return "";
        }
        if (isLatin) {
            return ownWord;
        }
        final String reading = LocalRomanizer.syllableReading(ownWord, fullLineText);
        return reading != null ? reading : "";
    }

    /**
     * The stored string stays the one drawn, so the words are measured against it and not
     * against the original.
     */
    static Reading wholeLineReading(LyricsLine line, int index,
                                    @Nullable List<LyricsLine> romanizedLines, @Nullable List<WordTiming> timings) {
        String whole = lineRomanizationAt(line, index, romanizedLines);
        if (whole == null || timings == null || timings.isEmpty()) {
            return new Reading(whole, null);
        }
        return new Reading(whole, spreadOverWords(whole, LyricsTextUtils.displayLineText(line), timings));
    }

    static boolean anyReading(List<Word> words) {
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
    static boolean sameWrittenWord(int from, int start,
                                   @Nullable String previous, String current) {
        if (previous == null || previous.isEmpty() || current.isEmpty() || start != from) {
            return false;
        }
        return !spellsSyllable(previous) && !spellsSyllable(current);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean spellsSyllable(String piece) {
        return !piece.isEmpty() && (isCjkAt(piece, 0) || isCjkAt(piece, piece.length() - 1));
    }

    static boolean isCjkAt(String text, int index) {
        final char c = text.charAt(index);
        if (Character.isHighSurrogate(c) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1))) {
            return LyricsWordBoundaries.isCjkCodePoint(Character.toCodePoint(c, text.charAt(index + 1)));
        }
        return LyricsWordBoundaries.isCjkCodePoint(c);
    }

    /**
     * An aligned reading rides in the romanization's own lines; the lyric's own words are read
     * only when they already carry one, as a provider's ruby does.
     */
    static List<Word> readingWords(LyricsLine line, int index,
                                   @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines != null && index < romanizedLines.size()) {
            List<Word> words = romanizedLines.get(index).words();
            if (!words.isEmpty()) {
                return words;
            }
        }
        return line.words();
    }

    static List<WordTiming> closeReadingGaps(List<WordTiming> spans, LyricsLine line) {
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
                    span.romaji(), span.hasTrailingSpace(), span.ruby(), span.averaged())
                    : span);
        }
        return closed;
    }

    /** The ruby of a line's words, leaving out ruby that repeats the word as written. */
    @Nullable
    static String wholeLineRuby(LyricsLine line) {
        StringBuilder ruby = new StringBuilder();
        for (Word word : line.words()) {
            if (word.ruby() != null && !word.ruby().isEmpty()
                    && !word.ruby().equalsIgnoreCase(word.text().trim())) {
                ruby.append(word.ruby());
            }
            if (word.endsWithSpace()) {
                ruby.append(' ');
            }
        }
        final String trimmed = ruby.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The line's own romanization, without falling back to the per-word one. */
    @Nullable
    static String lineRomanizationAt(LyricsLine line, int index,
                                     @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines == null || index >= romanizedLines.size()) {
            return null;
        }
        String origTrimmed = LyricsTextUtils.displayLineText(line).trim();
        if (origTrimmed.isEmpty() || LyricsRomanizer.isPurelyLatinScript(origTrimmed)) {
            return null;
        }
        String roma = romanizedLines.get(index).text();
        if (roma == null) {
            return null;
        }
        if (LyricsTextUtils.isSecondaryLine(line)) {
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

    static boolean hasAnyRuby(LyricsLine line) {
        for (Word word : line.words()) {
            if (word.ruby() != null && !word.ruby().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasAnyRuby(List<WordTiming> timings) {
        for (WordTiming timing : timings) {
            if (timing.ruby() != null && !timing.ruby().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Text and words come back as one, so no word is lit at an offset from another string. */
    record Romanized(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    static Romanized romanizedWithTimings(LyricsLine line, int index,
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
    static List<WordTiming> spreadOverWords(String whole, String text,
                                            @Nullable List<WordTiming> lineTimings) {
        if (lineTimings == null || lineTimings.isEmpty()) {
            return null;
        }
        List<Word> words = wordsAt(text, lineTimings);
        if (words.isEmpty()) {
            return null;
        }
        // A packed CJK reading is split into syllables when that makes it fit the words.
        String effective = LocalRomanizer.readingForWords(words, whole);
        int[][] tokens = LocalRomanizer.readingTokens(effective);
        if (tokens.length == 0) {
            return null;
        }
        int[][] spread = LocalRomanizer.spreadReading(words, effective);
        if (spread == null) {
            return null;
        }
        List<WordTiming> spans = new ArrayList<>(tokens.length);
        boolean averaged = isEntirelyAveraged(lineTimings);
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            final int from = spread[i][0];
            final int to = Math.min(spread[i][1], tokens.length);
            if (to <= from) {
                continue;
            }
            spreadWordWindow(spans, tokens, from, to, word.startMs(),
                    windowEndFor(words, i, word.endMs()), averaged);
        }
        return spans.isEmpty() ? null : spans;
    }

    /**
     * Runs to the next word's start: a silence is swept rather than stalled on, and two words
     * whose timings overlap cannot light out of order.
     */
    static long windowEndFor(List<Word> words, int index, long wordEndMs) {
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
    static void spreadWordWindow(List<WordTiming> out, int[][] tokens,
                                 int from, int to, long startMs, long endMs,
                                 boolean averaged) {
        int spelling = 0;
        for (int at = from; at < to; at++) {
            spelling += tokens[at][1] - tokens[at][0];
        }
        final long window = endMs - startMs;
        if (spelling <= 0 || window <= 0) {
            // Nothing to divide by, so the syllables share what there is.
            final long end = Math.max(endMs, startMs + 1);
            for (int at = from; at < to; at++) {
                out.add(new WordTiming(tokens[at][0], tokens[at][1], startMs, end,
                        null, false, null, averaged));
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
            out.add(new WordTiming(tokens[at][0], tokens[at][1], cursor, share,
                    null, false, null, averaged));
            cursor = share;
        }
    }

    /** One word per timing, which is all the placement needs to know about the line. */
    static List<Word> wordsAt(String text, List<WordTiming> lineTimings) {
        List<Word> words = new ArrayList<>(lineTimings.size());
        for (WordTiming timing : lineTimings) {
            final int start = timing.start();
            final int end = Math.min(timing.end(), text.length());
            if (start < 0 || start >= end) {
                continue;
            }
            words.add(new Word(timing.startMs(), timing.endMs(), text.substring(start, end),
                    timing.romaji(), timing.hasTrailingSpace(), timing.ruby()));
        }
        return words;
    }

    /** What a romanization-only line shows: its own, else the per-word one joined up. */
    @Nullable
    public static String romanizationForOnlyMode(LyricsLine line, int index,
                                                 @Nullable List<LyricsLine> romanizedLines) {
        return romanizedWithTimings(line, index, romanizedLines, null).text();
    }

    /**
     * The reading a line shows, laid out beside the line rather than over its words.
     *
     * <p>Only a lyric the user asked to be romanized reports per-word readings, so joining
     * those up cannot put a reading on a line the user never asked for one on.
     */
    @SuppressWarnings("unused")
    @Nullable
    static String lineReading(LyricsLine line, int index,
                              @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji) {
        return readingFor(line, index, romanizedLines, perWordRomaji, null).text();
    }
}

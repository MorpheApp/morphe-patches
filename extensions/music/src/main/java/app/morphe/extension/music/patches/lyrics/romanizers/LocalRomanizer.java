/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector.Script;
import app.morphe.extension.music.settings.Settings;

/**
 * Romanizes lyrics on the device, for the lines no service could romanize.
 *
 * <p>This runs last, after the provider, the AI endpoint and Google have all come back empty,
 * so what it produces is close to, but not the same as, what a service produces. A line in a
 * script nothing here covers is passed through rather than guessed at.
 *
 * <p>A word the dictionary does not have is left in the script it was written in, since
 * dropping the line would lose the words around it.
 */
public final class LocalRomanizer {


    public static final class Result {
        /** One line per input line. */
        public final List<String> lines;

        /** The name shown under the source. */
        @Nullable
        public final String dictionary;

        Result(List<String> lines, @Nullable String dictionary) {
            this.lines = lines;
            this.dictionary = dictionary;
        }
    }

    private record Read(String text, @Nullable String dictionary) {
    }

    private static final String PINYIN = "UniDict, pinyin-pro";
    private static final String JYUTPING = "UniDict, Rime Cantonese";
    private static final String RULE = "Built-in Romanization";
    private static final String JAPANESE = "SudachiDict";

    /** No syllables at all, and a word said with none of them. */
    private static final int[][] NO_TOKENS = new int[0][];
    private static final int[] NO_SYLLABLES = {0, 0};

    /** Words ending in the er character where it is a word of its own rather than the erhua suffix. */
    private static final Set<String> PLAIN_ERHUA = new HashSet<>(Arrays.asList(
            "女儿", "男儿", "婴儿", "幼儿", "孤儿", "少儿", "胎儿", "健儿", "残儿", "弃儿",
            "新生儿", "低能儿", "弱智儿", "托儿", "孕儿"));

    private LocalRomanizer() {
    }

    /** Null when nothing here applies, which tells the caller to leave the lyrics alone. */
    @Nullable
    public static Result romanize(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        if (ScriptDetector.detect(lines) == Script.OTHER) return null;

        final boolean cantonese = cantonese();
        boolean changed = false;
        String dictionary = null;
        List<String> result = new ArrayList<>(lines.size());

        for (String line : lines) {
            Read read = readAs(line, ScriptDetector.detect(line), cantonese);
            if (read.dictionary() != null) dictionary = read.dictionary();
            if (!read.text().equals(line)) changed = true;
            result.add(read.text());
        }

        return changed ? new Result(result, dictionary) : null;
    }

    /**
     * Only the reading is added, and only to the lines the source had split: a line it did not
     * split keeps the whole-line reading passed in.
     */
    @Nullable
    public static List<LyricsLine> alignWords(List<LyricsLine> lines,
            List<LyricsLine> wholeLine) {
        if (lines == null || wholeLine == null || lines.size() != wholeLine.size()) return null;

        final boolean cantonese = cantonese();
        List<LyricsLine> result = new ArrayList<>(lines.size());
        boolean aligned = false;

        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            // Kana is what marks a line Japanese; a word of kanji alone says only Han, and would
            // then be looked up in the wrong dictionary.
            Script script = ScriptDetector.detect(line.text());
            List<Word> read = exactReadings(line.words(), script, cantonese);
            if (read == null) {
                read = splitReading(line, wholeLine.get(i).text());
            }

            if (read == null) {
                result.add(wholeLine.get(i));
                continue;
            }
            attachPunctuation(read);
            result.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), read,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
            aligned = true;
        }

        return aligned ? result : null;
    }

    /** Null when not one word could be read, leaving the line to {@link #splitReading}. */
    @Nullable
    private static List<Word> exactReadings(List<Word> words, @Nullable Script script,
            boolean cantonese) {
        if (words.isEmpty() || script == null) return null;
        List<Word> read = new ArrayList<>(words.size());
        boolean any = false;

        for (Word word : words) {
            String reading = readAs(word.text(), script, cantonese).text();
            if (reading.equals(word.text())) {
                read.add(word);
                continue;
            }
            read.add(new Word(word.startMs(), word.endMs(), word.text(), reading,
                    word.endsWithSpace()));
            any = true;
        }

        return any ? read : null;
    }

    /**
     * A whole reading laid onto the words the source split the line into. Only two shapes place without
     * guessing: one syllable per spoken character, or one per word; anything else keeps the whole line,
     * a reading on the wrong word being worse than none.
     */
    @Nullable
    private static List<Word> splitReading(LyricsLine line, @Nullable String wholeLine) {
        List<Word> words = line.words();
        if (wholeLine == null || words.isEmpty()) return null;
        String original = line.text();
        String reading = wholeLine.trim();
        if (reading.isEmpty() || original == null || reading.equalsIgnoreCase(original.trim())) {
            return null;
        }

        int[][] tokens = readingTokens(wholeLine);
        int[][] spread = spreadReading(words, wholeLine);
        if (tokens.length == 0 || spread == null) return null;

        List<Word> read = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            int[] range = spread[i];
            if (range[1] <= range[0]) {
                read.add(word);
                continue;
            }
            StringBuilder syllables = new StringBuilder();
            for (int at = range[0]; at < range[1]; at++) {
                if (syllables.length() > 0) syllables.append(' ');
                syllables.append(reading, tokens[at][0], tokens[at][1]);
            }
            read.add(new Word(word.startMs(), word.endMs(), word.text(), syllables.toString(),
                    word.endsWithSpace()));
        }
        return read;
    }

    /**
     * Where each syllable of a whole reading starts and ends, as {@code [start, end)}. A run of marks
     * carries no syllable of its own and is left out.
     */
    public static int[][] readingTokens(@Nullable String reading) {
        if (reading == null) {
            return NO_TOKENS;
        }
        List<int[]> tokens = new ArrayList<>();
        int at = 0;
        while (at < reading.length()) {
            while (at < reading.length() && Character.isWhitespace(reading.charAt(at))) at++;
            final int start = at;
            while (at < reading.length() && !Character.isWhitespace(reading.charAt(at))) at++;
            if (at > start && speaks(reading.substring(start, at))) {
                tokens.add(new int[] {start, at});
            }
        }
        return tokens.isEmpty() ? NO_TOKENS : tokens.toArray(new int[0][]);
    }

    /**
     * Which of those syllables each word is said with, as {@code [from, to)} token indices and an empty
     * pair for a word of marks only. Null when the reading fits the words neither one syllable per
     * spoken character nor one per word.
     *
     * <p>A caller holding only timings gets the same placement by handing over one word per timing.
     */
    @Nullable
    public static int[][] spreadReading(List<Word> words, @Nullable String reading) {
        int[][] tokens = readingTokens(reading);
        if (tokens.length == 0 || words.isEmpty()) return null;

        int[] spoken = new int[words.size()];
        int syllables = 0;
        int carried = 0;
        for (int i = 0; i < words.size(); i++) {
            spoken[i] = units(words.get(i).text());
            if (spoken[i] > 0) carried++;
            syllables += spoken[i];
        }

        final int length = tokens.length;
        if (length != syllables && length != carried) return null;

        int[][] spread = new int[words.size()][];
        int token = 0;
        int placed = 0;
        for (int i = 0; i < words.size(); i++) {
            spread[i] = NO_SYLLABLES;
            if (spoken[i] <= 0) continue;
            placed += spoken[i];
            final int end = length == syllables ? placed : token + 1;
            spread[i] = new int[] {token, end};
            token = end;
        }
        return spread;
    }

    /**
     * How much of a whole reading one word is said with. Han, Hangul and kana are a syllable to the
     * character, so their count is the characters; every other writing counts as the whole word,
     * letters being no syllables there and reading them apart cutting a word into pieces.
     */
    private static int units(String word) {
        int characters = 0;
        int letters = 0;
        for (int i = 0; i < word.length(); i++) {
            final char c = word.charAt(i);
            if (Character.isLetterOrDigit(c)) letters++;
            if (isOnePerSyllable(c)) characters++;
        }
        if (characters == 0) return letters > 0 ? 1 : 0;
        // The erhua suffix rides on the syllable before it, so such a word is said one fewer than written,
        // unless the er is a word of its own: anything else beside it keeps its syllable, or the er has none.
        if (characters > 1 && letters > 0 && word.endsWith("儿") && !PLAIN_ERHUA.contains(word)) {
            characters--;
        }
        return characters;
    }

    private static boolean isOnePerSyllable(char c) {
        return ScriptDetector.isHan(c) || ScriptDetector.isKana(c)
                || ScriptDetector.isHangul(c);
    }

    /** A word of nothing but punctuation has no reading of its own, so it rides on the one before. */
    private static void attachPunctuation(List<Word> read) {
        for (int i = 0; i < read.size(); i++) {
            if (read.get(i).romaji() != null) continue;
            String text = read.get(i).text().trim();
            if (text.isEmpty() || speaks(text)) continue;
            for (int at = i - 1; at >= 0; at--) {
                Word previous = read.get(at);
                // One that already gave its marks away has nothing left to carry more.
                if (previous.romaji() == null || previous.romaji().isEmpty()) continue;
                if (!previous.romaji().endsWith(text)) {
                    read.set(at, new Word(previous.startMs(), previous.endMs(), previous.text(),
                            previous.romaji() + text, previous.endsWithSpace()));
                }
                read.set(i, new Word(read.get(i).startMs(), read.get(i).endMs(),
                        read.get(i).text(), "", read.get(i).endsWithSpace()));
                break;
            }
        }
    }

    /** Whether the text is said at all, as against being only the marks around it. */
    private static boolean speaks(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) return true;
        }
        return false;
    }

    private static Read readAs(String text, Script script, boolean cantonese) {
        switch (script) {
            case HAN:
                if (cantonese) {
                    return CantoneseDictStore.isReady()
                            ? new Read(JyutpingRomanizer.romanize(text), JYUTPING)
                            : new Read(text, null);
                }
                return PinyinDictStore.isReady()
                        ? new Read(PinyinRomanizer.romanize(text), PINYIN)
                        : new Read(text, null);
            case JAPANESE:
                // Kana read by rule are not the dictionary's work, so nothing is credited.
                return new Read(japanese(text), JapaneseDictStore.isReady() ? JAPANESE : null);
            case KOREAN:
                return new Read(HangulRomanizer.romanize(text), RULE);
            case GREEK:
                return new Read(GreekRomanizer.romanize(text), RULE);
            case CYRILLIC:
                return new Read(CyrillicRomanizer.romanize(text), RULE);
            case GEORGIAN:
                return new Read(GeorgianRomanizer.romanize(text), RULE);
            case ARMENIAN:
                return new Read(ArmenianRomanizer.romanize(text), RULE);
            default:
                return new Read(text, null);
        }
    }

    /** Cantonese rather than Chinese, which the Han text itself cannot say. */
    private static boolean cantonese() {
        return "YUE".equalsIgnoreCase(Settings.LYRICS_ROMANIZE_LANGUAGE.get());
    }

    /** Kana by rule, everything else out of the dictionary. */
    private static String japanese(String line) {
        WordReadingTable table = JapaneseDictStore.table();
        StringBuilder out = new StringBuilder(line.length() * 2);
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);
            if (ScriptDetector.isKana(c)) {
                int end = i;
                while (end < line.length() && ScriptDetector.isKana(line.charAt(end))) end++;
                kana(line, i, end, table, out);
                i = end;
                continue;
            }
            if (ScriptDetector.isHan(c)) {
                if (table == null) {
                    out.append(c);
                    i++;
                    continue;
                }
                // The longest entry that fits: a shorter one inside a longer word reads it wrong.
                int limit = Math.min(WordReadingTable.MAX_WORD_LENGTH, line.length() - i);
                String reading = null;
                int length = 0;
                for (int candidate = limit; candidate >= 1; candidate--) {
                    String found = table.reading(line.substring(i, i + candidate));
                    if (found != null && !found.isEmpty()) {
                        reading = found;
                        length = candidate;
                        break;
                    }
                }
                if (reading == null) {
                    out.append(c);
                    i++;
                    continue;
                }
                append(out, KanaRomanizer.romanize(reading));
                i += length;
                continue;
            }
            // Anything else, such as a word in Latin letters, kept whole rather than per letter.
            int end = i;
            while (end < line.length() && !ScriptDetector.isKana(line.charAt(end))
                    && !ScriptDetector.isHan(line.charAt(end))) {
                end++;
            }
            append(out, line.substring(i, end).trim());
            i = end;
        }
        return out.toString().trim();
    }

    /** A run of kana, cut into words: the rule reads it either way, but without the words a
     * line reads as {@code chottomatte} where Google gives {@code Chotto matte}. */
    private static void kana(String line, int from, int to, WordReadingTable table,
            StringBuilder out) {
        int at = from;
        while (at < to) {
            int length = to - at;
            if (table != null) {
                int limit = Math.min(WordReadingTable.MAX_WORD_LENGTH, to - at);
                for (int candidate = limit; candidate >= 1; candidate--) {
                    if (table.reading(line.substring(at, at + candidate)) != null) {
                        length = candidate;
                        break;
                    }
                }
            }
            append(out, KanaRomanizer.romanize(line.substring(at, at + length)));
            at += length;
        }
    }

    private static void append(StringBuilder out, String word) {
        if (word.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(word);
    }
}

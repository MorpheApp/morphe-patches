/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.derive.ProcessingProviders;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Adds ruby, the reading of each character or word, to lyrics as the ruby of its word. Handles
 * Japanese furigana (kanji readings).
 *
 * <p>Ruby the source ships and the offline dictionary readings are added right away. An AI
 * provider answers in the background and only fills the words still without a reading.
 */
public final class RubyGenerator {

    private static final String LANGUAGE_DEFAULT = "DEFAULT";
    private static final String LANGUAGE_NONE = "NONE";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-ruby-generator");
        thread.setDaemon(true);
        return thread;
    });

    private RubyGenerator() {
    }

    private static List<String> enabledProviders() {
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        if (LANGUAGE_NONE.equalsIgnoreCase(rubyLanguage)) {
            return Collections.emptyList();
        }
        final List<String> enabled = ProcessingProviders.enabledProviders(
                Settings.LYRICS_ROMANIZATION_PROVIDERS.get(), ProcessingProviders.ROMANIZATION_ORDER);
        // Automatic only reads the ruby the source ships.
        if (LANGUAGE_DEFAULT.equalsIgnoreCase(rubyLanguage)) {
            return enabled.contains(ProcessingProviders.EMBEDDED)
                    ? List.of(ProcessingProviders.EMBEDDED)
                    : Collections.emptyList();
        }
        return enabled;
    }

    /**
     * The ruby the source ships, or none if that provider is off, plus the offline dictionary
     * readings once the dictionary is downloaded.
     */
    public static Lyrics applyLocal(Lyrics lyrics) {
        if (!hasKanjiOrRuby(lyrics.lines())) {
            return lyrics;
        }
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        final RubyStyle style = rubyStyleFromLanguage(rubyLanguage);
        // Automatic keeps the source's own ruby; a manual language clears it so the dictionaries
        // and AI provide every reading. If they have no reading for a word, that word stays bare
        // rather than falling back to the source's kana.
        final boolean automatic = LANGUAGE_DEFAULT.equalsIgnoreCase(rubyLanguage);
        final Lyrics current = formatEmbeddedRuby(lyrics,
                automatic && enabledProviders().contains(ProcessingProviders.EMBEDDED), style);
        if (automatic || LANGUAGE_NONE.equalsIgnoreCase(rubyLanguage)) {
            return current;
        }

        // A manual language is read by its own dictionary, whichever romanization providers are on.
        if (isJapaneseRubyLanguage(rubyLanguage)) {
            final WordReadingTable table = JapaneseDictStore.table();
            return table != null ? enrichFromDictionary(current, table, style) : current;
        }
        // What is left is a language the user added a Rime word table for.
        final WordReadingTable words = RimeDictStore.word(rubyLanguage);
        if (words == null || !RimeDictStore.isReady(rubyLanguage)) {
            return current;
        }
        return enrichByCharacter(current, words, text -> {
            final String reading = lookupExactReading(text, words);
            return reading != null ? reading
                    : characterReading(text, c -> RimeDictStore.reading(c, rubyLanguage));
        }, style);
    }

    /** Whether {@link #requestAi} has anything to do. */
    public static boolean usesAi(Lyrics lyrics) {
        for (String provider : enabledProviders()) {
            if (ProcessingProviders.isAi(provider)) {
                return hasKanjiOrRuby(lyrics.lines());
            }
        }
        return false;
    }

    /**
     * Asks the enabled AI provider for the readings the lyrics still lack. The callback runs on
     * the main thread, with the lyrics unchanged if the provider gave no usable answer.
     */
    public static void requestAi(TrackInfo track, Lyrics lyrics, Callback callback) {
        final RubyStyle style = rubyStyleFromLanguage(Settings.LYRICS_RUBY_LANGUAGE.get());
        WORKER.execute(() -> {
            Lyrics result = lyrics;
            try {
                List<String> lines = new ArrayList<>(lyrics.lines().size());
                for (LyricsLine line : lyrics.lines()) {
                    lines.add(line.text().trim());
                }
                for (String provider : enabledProviders()) {
                    if (!ProcessingProviders.isAi(provider)) {
                        continue;
                    }
                    List<String> aiLines = LyricsCache.getRubyAI(
                            track, lyrics.providerName(), lines, provider);
                    if (aiLines == null) {
                        String prompt = OpenAIClient.renderPrompt(
                                Settings.LYRICS_AI_RUBY_PROMPT.get(), "furigana",
                                LyricsRequests.rubyLanguage(),
                                track.title(), track.artist(), lines);
                        ProcessingProviders.Result ai = ProcessingProviders.mapLines(provider, prompt, lines);
                        if (ai == null || ai.lines() == null || ai.lines().size() != lines.size()) {
                            continue;
                        }
                        aiLines = ai.lines();
                        LyricsCache.putRubyAI(track, lyrics.providerName(), lines, aiLines, provider);
                    }
                    result = enrichFromAiLines(lyrics, aiLines, JapaneseDictStore.table(), style);
                    break;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "requestAi failure", ex);
            }
            final Lyrics generated = result;
            Utils.runOnMainThread(() -> callback.onGenerated(generated));
        });
    }

    private static boolean hasKanjiOrRuby(List<LyricsLine> lines) {
        for (LyricsLine line : lines) {
            if (ScriptDetector.contains(line.text(), Character.UnicodeScript.HAN) || hasRuby(line)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRuby(LyricsLine line) {
        for (Word word : line.words()) {
            if (hasRuby(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRuby(Word word) {
        return word.ruby() != null && !word.ruby().isEmpty();
    }

    private static LyricsLine withWords(LyricsLine line, List<Word> words) {
        return new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), words,
                line.agentId(), line.isDuet(), line.isBG(), line.songPart());
    }

    private static Word withRuby(Word word, @Nullable String ruby) {
        return new Word(word.startMs(), word.endMs(), word.text(), word.romaji(),
                word.endsWithSpace(), ruby);
    }

    /** Puts the source's ruby in the chosen script, or drops it when that provider is off. */
    private static Lyrics formatEmbeddedRuby(Lyrics lyrics, boolean keep, RubyStyle style) {
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        boolean changed = false;
        for (LyricsLine line : lyrics.lines()) {
            if (!hasRuby(line)) {
                lines.add(line);
                continue;
            }
            List<Word> words = new ArrayList<>(line.words().size());
            for (Word word : line.words()) {
                words.add(hasRuby(word)
                        ? withRuby(word, keep ? formatRuby(word.ruby(), style) : null)
                        : word);
            }
            lines.add(withWords(line, words));
            changed = true;
        }
        return changed ? lyrics.withLines(lines) : lyrics;
    }

    /** A copy of the lyrics with every word's ruby taken off, for hiding the reading. */
    public static Lyrics stripRuby(Lyrics lyrics) {
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        boolean changed = false;
        for (LyricsLine line : lyrics.lines()) {
            if (!hasRuby(line)) {
                lines.add(line);
                continue;
            }
            List<Word> words = new ArrayList<>(line.words().size());
            for (Word word : line.words()) {
                words.add(hasRuby(word) ? withRuby(word, null) : word);
            }
            lines.add(withWords(line, words));
            changed = true;
        }
        return changed ? lyrics.withLines(lines) : lyrics;
    }

    private static Lyrics enrichFromDictionary(Lyrics lyrics, WordReadingTable table,
                                               RubyStyle style) {
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(enrichLineFromDictionary(line, table, style));
        }
        return lyrics.withLines(lines);
    }

    private static LyricsLine enrichLineFromDictionary(LyricsLine line, WordReadingTable table,
                                                       RubyStyle style) {
        if (!ScriptDetector.contains(line.text(), Character.UnicodeScript.HAN)) {
            return line;
        }
        // Each kanji word needs a word of its own to carry its reading.
        List<Word> words = segmentedWords(line, table);
        List<Word> read = new ArrayList<>(words.size());
        boolean changed = words.size() != line.words().size();

        int i = 0;
        while (i < words.size()) {
            Word word = words.get(i);
            String text = word.text().trim();
            if (!ScriptDetector.contains(text, Character.UnicodeScript.HAN)) {
                read.add(word);
                i++;
                continue;
            }

            // A word timed one kanji at a time, such as 以 and 上, reads as the pair it makes.
            if (text.length() == 1 && i + 1 < words.size()) {
                Word next = words.get(i + 1);
                String nextText = next.text().trim();
                if (nextText.length() == 1 && ScriptDetector.isHan(nextText.charAt(0))) {
                    String pair = text + nextText;
                    String pairReading = table.reading(pair);
                    String[] split = pairReading == null || pairReading.isEmpty()
                            ? null
                            : splitPairReading(pair, KanaRomanizer.toHiragana(pairReading), table);
                    if (split != null) {
                        read.add(withRuby(word, formatRuby(split[0], style)));
                        read.add(withRuby(next, formatRuby(split[1], style)));
                        changed = true;
                        i += 2;
                        continue;
                    }
                }
            }

            String reading = lookupReading(text, table);
            if (reading != null) {
                read.add(withRuby(word, formatRuby(stripOkurigana(text, reading), style)));
                changed = true;
            } else {
                read.add(word);
            }
            i++;
        }
        return changed ? withWords(line, read) : line;
    }

    /**
     * Gives each Chinese character its own word and its own reading, for the languages read a
     * character at a time.
     *
     * @param table  Where the line's words are cut, or null to cut it by character alone.
     * @param reader The reading of one character, or null when there is none.
     */
    private static Lyrics enrichByCharacter(Lyrics lyrics, @Nullable WordReadingTable table,
                                            UnaryOperator<String> reader, RubyStyle style) {
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        boolean changed = false;
        for (LyricsLine line : lyrics.lines()) {
            LyricsLine read = enrichLineByCharacter(line, table, reader, style);
            changed |= read != line;
            lines.add(read);
        }
        return changed ? lyrics.withLines(lines) : lyrics;
    }

    private static LyricsLine enrichLineByCharacter(LyricsLine line, @Nullable WordReadingTable table,
                                                    UnaryOperator<String> reader, RubyStyle style) {
        if (!ScriptDetector.contains(line.text(), Character.UnicodeScript.HAN)) {
            return line;
        }
        List<Word> words = splitHanToSingleChars(segmentedWords(line, table));
        List<Word> read = new ArrayList<>(words.size());
        boolean changed = false;
        for (Word word : words) {
            String text = word.text().trim();
            String reading = ScriptDetector.contains(text, Character.UnicodeScript.HAN)
                    ? reader.apply(text) : null;
            if (reading != null) {
                read.add(withRuby(word, formatRuby(reading, style)));
                changed = true;
            } else {
                read.add(word);
            }
        }
        return changed ? withWords(line, read) : line;
    }

    /** Splits every Han character into its own word, for the character-based reading. */
    private static List<Word> splitHanToSingleChars(List<Word> words) {
        List<Word> result = new ArrayList<>();
        for (Word word : words) {
            String text = word.text();
            int hanCount = 0;
            for (int i = 0; i < text.length(); i++) {
                if (ScriptDetector.isHan(text.charAt(i))) hanCount++;
            }
            if (hanCount <= 1) {
                result.add(word);
                continue;
            }
            // Each Han character becomes its own word; non-Han runs stay together.
            long startMs = word.startMs();
            long duration = Math.max(0L, word.endMs() - startMs);
            int totalLen = text.length();
            long cursorMs = startMs;
            int seenChars = 0;
            int i = 0;
            while (i < totalLen) {
                int j;
                if (ScriptDetector.isHan(text.charAt(i))) {
                    j = i + 1;
                } else {
                    j = i;
                    while (j < totalLen && !ScriptDetector.isHan(text.charAt(j))) j++;
                }
                seenChars += j - i;
                long wordEnd = startMs + duration * seenChars / totalLen;
                boolean endsWithSpace = (j == totalLen) && word.endsWithSpace();
                result.add(new Word(cursorMs, wordEnd, text.substring(i, j),
                        null, endsWithSpace, null));
                cursorMs = wordEnd;
                i = j;
            }
        }
        return result;
    }

    /**
     * The reading of a word a character at a time, for the words the word table does not have.
     * Characters the character tables do not cover are skipped, so a word that is only partly
     * covered still shows the reading of the characters that are covered.
     */
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    @Nullable
    private static String characterReading(String text, Function<Character, String> reader) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String reading = ScriptDetector.isHan(c) ? reader.apply(c) : null;
            if (reading != null) {
                if (out.length() > 0) out.append(' ');
                out.append(reading);
            }
        }
        return out.length() > 0 ? out.toString() : null;
    }

    /**
     * The line's words, with a word that mixes katakana and kanji or runs several kanji words
     * together cut up. A line without words is cut up the same way, and since it has no timing
     * of its own every word is sung as the line starts, as the line itself is.
     */
    private static List<Word> segmentedWords(LyricsLine line, @Nullable WordReadingTable table) {
        if (!line.hasWords()) {
            final long lineStart = Math.max(0L, line.startTimeMs());
            return segmentText(line.text(), lineStart, lineStart, table);
        }

        List<Word> expanded = new ArrayList<>(line.words().size());
        for (Word word : line.words()) {
            if (!hasRuby(word) && needsSplit(word.text(), table)) {
                List<Word> parts = segmentText(word.text(), word.startMs(), word.endMs(), table);
                if (parts.size() > 1) {
                    Word last = parts.get(parts.size() - 1);
                    parts.set(parts.size() - 1, new Word(last.startMs(), last.endMs(),
                            last.text(), last.romaji(), word.endsWithSpace(), null));
                    expanded.addAll(parts);
                    continue;
                }
            }
            expanded.add(word);
        }
        return expanded;
    }

    /**
     * Whether a word mixes katakana with hiragana or kanji, such as ノルマ以下, or holds several
     * kanji words, such as 深く朱き.
     */
    private static boolean needsSplit(String text, @Nullable WordReadingTable table) {
        String clean = text.trim();
        if (clean.length() <= 1) {
            return false;
        }
        boolean hasKatakana = false;
        boolean hasHiragana = false;
        boolean hasHan = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (isKatakana(c)) hasKatakana = true;
            else if (isHiragana(c)) hasHiragana = true;
            else if (ScriptDetector.isHan(c)) hasHan = true;
        }
        if (hasKatakana && (hasHiragana || hasHan)) {
            return true;
        }
        if (!hasHan || lookupExactReading(clean, table) != null) {
            return false;
        }
        // Kanji after kana starts another word.
        boolean seenHan = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (ScriptDetector.isHan(c)) {
                if (seenHan && ScriptDetector.isKana(clean.charAt(i - 1))) {
                    return true;
                }
                seenHan = true;
            }
        }
        // A long word the dictionary knows only the start of.
        return clean.length() >= 4 && lookupReading(clean, table) != null;
    }

    /**
     * Cuts Japanese text into kanji words with their okurigana, katakana runs, hiragana runs and
     * anything else, each timed by its share of the characters.
     */
    private static List<Word> segmentText(String text, long startMs, long endMs,
                                          @Nullable WordReadingTable table) {
        List<String> tokens = new ArrayList<>();
        List<Boolean> trailingSpaces = new ArrayList<>();

        final int n = text.length();
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) break;

            char c = text.charAt(i);
            int end;
            if (ScriptDetector.isHan(c)) {
                end = kanjiWordEnd(text, i, table);
            } else if (isKatakana(c)) {
                end = i + 1;
                while (end < n && isKatakana(text.charAt(end))) end++;
            } else if (isHiragana(c)) {
                end = i + 1;
                while (end < n && isHiragana(text.charAt(end))) end++;
            } else {
                end = i + 1;
                while (end < n && !Character.isWhitespace(text.charAt(end))
                        && !ScriptDetector.isHan(text.charAt(end))
                        && !ScriptDetector.isKana(text.charAt(end))) {
                    end++;
                }
            }
            // Punctuation stays with the word before it.
            while (end < n) {
                char p = text.charAt(end);
                if (Character.isWhitespace(p) || Character.isLetterOrDigit(p)
                        || ScriptDetector.isHan(p) || ScriptDetector.isKana(p)) {
                    break;
                }
                end++;
            }

            tokens.add(text.substring(i, end));
            trailingSpaces.add(end < n && Character.isWhitespace(text.charAt(end)));
            i = end;
        }
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        int totalChars = 0;
        for (String token : tokens) {
            totalChars += token.length();
        }
        final long duration = Math.max(0L, endMs - startMs);
        long cursorMs = startMs;
        int seenChars = 0;
        List<Word> words = new ArrayList<>(tokens.size());
        for (int idx = 0; idx < tokens.size(); idx++) {
            seenChars += tokens.get(idx).length();
            long wordEnd = startMs + duration * seenChars / totalChars;
            words.add(new Word(cursorMs, wordEnd, tokens.get(idx), null, trailingSpaces.get(idx), null));
            cursorMs = wordEnd;
        }
        return words;
    }

    /** The end of the longest dictionary word of kanji and okurigana starting at {@code start}. */
    private static int kanjiWordEnd(String text, int start, @Nullable WordReadingTable table) {
        final int n = text.length();
        final int maxLength = Math.min(WordReadingTable.MAX_WORD_LENGTH, n - start);
        for (int length = maxLength; length >= 2; length--) {
            if (isKanjiWithOkurigana(text, start, start + length)
                    && lookupExactReading(text.substring(start, start + length), table) != null) {
                return start + length;
            }
        }

        int end = start + 1;
        while (end < n && ScriptDetector.isHan(text.charAt(end))) {
            if (lookupExactReading(text.substring(start, end), table) != null) {
                break;
            }
            end++;
        }
        // No dictionary word here: take just this character, not the whole rest of the line.
        return end < n ? end : start + 1;
    }

    /** Kanji followed by nothing but hiragana. */
    private static boolean isKanjiWithOkurigana(String text, int start, int end) {
        boolean seenKana = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isHiragana(c)) {
                seenKana = true;
            } else if (seenKana || !ScriptDetector.isHan(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKatakana(char c) {
        return (c >= 'ァ' && c <= 'ヺ') || c == '・' || c == 'ー';
    }

    private static boolean isHiragana(char c) {
        return c >= 'ぁ' && c <= 'ゖ';
    }

    /** The reading of the kanji alone, without the okurigana the word ends with. */
    static String stripOkurigana(String wordText, String reading) {
        String hiraReading = KanaRomanizer.toHiragana(reading.trim());
        String hiraWord = KanaRomanizer.toHiragana(wordText.trim());

        int wordEnd = hiraWord.length();
        int readingEnd = hiraReading.length();
        while (wordEnd > 0 && readingEnd > 1) {
            char c = hiraWord.charAt(wordEnd - 1);
            if (!ScriptDetector.isKana(c) || c != hiraReading.charAt(readingEnd - 1)) {
                break;
            }
            wordEnd--;
            readingEnd--;
        }
        return readingEnd < hiraReading.length() ? hiraReading.substring(0, readingEnd) : reading;
    }

    /** Splits the reading of two kanji between them, starting from the first one's own reading. */
    @Nullable
    private static String[] splitPairReading(String pair, String reading, WordReadingTable table) {
        String first = table.reading(pair.substring(0, 1));
        if (first != null && !first.isEmpty()) {
            String hiraFirst = KanaRomanizer.toHiragana(first);
            if (reading.startsWith(hiraFirst) && reading.length() > hiraFirst.length()) {
                return new String[]{hiraFirst, reading.substring(hiraFirst.length())};
            }
        }
        if (reading.length() >= 2) {
            return new String[]{reading.substring(0, 1), reading.substring(1)};
        }
        return null;
    }

    @Nullable
    private static String lookupExactReading(String text, @Nullable WordReadingTable table) {
        if (table == null) {
            return null;
        }
        String reading = table.reading(text);
        return reading == null || reading.isEmpty() ? null : reading;
    }

    /** The reading of the word, or else of the longest start of it the dictionary knows. */
    @Nullable
    private static String lookupReading(String text, @Nullable WordReadingTable table) {
        for (int length = Math.min(WordReadingTable.MAX_WORD_LENGTH, text.length()); length >= 1; length--) {
            String reading = lookupExactReading(text.substring(0, length), table);
            if (reading != null) {
                return reading;
            }
        }
        return null;
    }

    /** Gives each kanji word without a reading the one the AI wrote in brackets after it. */
    static Lyrics enrichFromAiLines(Lyrics lyrics, List<String> aiLines,
                                    @Nullable WordReadingTable table, RubyStyle style) {
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        for (int li = 0; li < lyrics.lines().size(); li++) {
            LyricsLine line = lyrics.lines().get(li);
            Map<String, String> readings = li < aiLines.size() && aiLines.get(li) != null
                    ? parseAiReadings(aiLines.get(li))
                    : Collections.emptyMap();
            if (readings.isEmpty()) {
                lines.add(line);
                continue;
            }

            List<Word> words = segmentedWords(line, table);
            List<Word> read = new ArrayList<>(words.size());
            boolean changed = words.size() != line.words().size();
            for (Word word : words) {
                String clean = word.text().trim();
                String reading = hasRuby(word) || !ScriptDetector.contains(clean, Character.UnicodeScript.HAN)
                        ? null
                        : aiReading(clean, readings);
                if (reading == null) {
                    read.add(word);
                } else {
                    read.add(withRuby(word, formatRuby(stripOkurigana(clean, reading), style)));
                    changed = true;
                }
            }
            lines.add(changed ? withWords(line, read) : line);
        }
        return lyrics.withLines(lines);
    }

    @Nullable
    private static String aiReading(String word, Map<String, String> readings) {
        String reading = readings.get(word);
        if (reading != null) {
            return reading;
        }
        // The word may carry punctuation the AI wrote apart from it.
        for (Map.Entry<String, String> entry : readings.entrySet()) {
            if (word.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * The readings an AI line holds, by the kanji and okurigana right before each bracket:
     * 君[きみ]の名前[なまえ] reads 君 and 名前.
     */
    private static Map<String, String> parseAiReadings(String aiLine) {
        Map<String, String> readings = new HashMap<>();
        int open = aiLine.indexOf('[');
        while (open >= 0) {
            final int close = aiLine.indexOf(']', open);
            if (close < 0) {
                break;
            }
            int start = open;
            while (start > 0 && ScriptDetector.isKana(aiLine.charAt(start - 1))) start--;
            final int kanaStart = start;
            while (start > 0 && ScriptDetector.isHan(aiLine.charAt(start - 1))) start--;
            String reading = aiLine.substring(open + 1, close).trim();
            if (start < kanaStart && !reading.isEmpty()) {
                readings.put(aiLine.substring(start, open), reading);
            }
            open = aiLine.indexOf('[', close);
        }
        return readings;
    }

    /** Kana ruby in the chosen script. Other ruby, such as an English gloss, is kept as is. */
    public static String formatRuby(String ruby, RubyStyle style) {
        if (!ScriptDetector.containsKana(ruby)) {
            return ruby;
        }
        return style == RubyStyle.KATAKANA
                ? KanaRomanizer.toKatakana(ruby)
                : KanaRomanizer.toHiragana(ruby);
    }

    static RubyStyle rubyStyleFromLanguage(String language) {
        if ("ja-Kana".equalsIgnoreCase(language)) {
            return RubyStyle.KATAKANA;
        }
        return RubyStyle.HIRAGANA;
    }

    /** Whether the ruby language is the one the shipped Japanese dictionary reads. */
    public static boolean isJapaneseRubyLanguage(String language) {
        return "ja".equalsIgnoreCase(language)
                || "ja-Hira".equalsIgnoreCase(language)
                || "ja-Kana".equalsIgnoreCase(language);
    }

    public enum RubyStyle {
        HIRAGANA,
        KATAKANA
    }

    public interface Callback {
        void onGenerated(Lyrics lyrics);
    }
}

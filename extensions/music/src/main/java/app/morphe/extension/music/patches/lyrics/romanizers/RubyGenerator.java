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
 * Japanese furigana (kanji readings) and Chinese bopomofo (注音) alike.
 *
 * <p>Ruby the source ships and the offline dictionary readings are added right away. An AI
 * provider answers in the background and only fills the words still without a reading.
 */
public final class RubyGenerator {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-ruby-generator");
        thread.setDaemon(true);
        return thread;
    });

    private RubyGenerator() {
    }

    private static List<String> enabledProviders() {
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        if ("NONE".equalsIgnoreCase(rubyLanguage)) {
            return Collections.emptyList();
        }
        final List<String> enabled = ProcessingProviders.enabledProviders(
                Settings.LYRICS_ROMANIZATION_PROVIDERS.get(), ProcessingProviders.ROMANIZATION_ORDER);
        // Automatic only reads the ruby the source ships; a manually chosen language also
        // reads the offline dictionaries, the way romaji does.
        if ("DEFAULT".equalsIgnoreCase(rubyLanguage)) {
            return enabled.contains(ProcessingProviders.EMBEDDED)
                    ? List.of(ProcessingProviders.EMBEDDED)
                    : Collections.emptyList();
        }
        List<String> providers = new ArrayList<>(enabled);
        // Ruby must have LOCAL and RIME even when disabled by default.
        if (!providers.contains(ProcessingProviders.LOCAL)
                && ProcessingProviders.ROMANIZATION_ORDER.contains(ProcessingProviders.LOCAL)) {
            providers.add(ProcessingProviders.LOCAL);
        }
        if (!providers.contains(ProcessingProviders.RIME)
                && ProcessingProviders.ROMANIZATION_ORDER.contains(ProcessingProviders.RIME)) {
            providers.add(ProcessingProviders.RIME);
        }
        return providers;
    }

    /**
     * The ruby the source ships, or none if that provider is off, plus the offline dictionary
     * readings once the dictionary is downloaded.
     */
    public static Lyrics applyLocal(Lyrics lyrics) {
        if (!hasKanjiOrRuby(lyrics.lines())) {
            return lyrics;
        }
        final List<String> providers = enabledProviders();
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        final RubyStyle style = rubyStyleFromLanguage(rubyLanguage);
        // DEFAULT keeps the source's own ruby; a manual language clears it so the
        // dictionaries and AI provide every reading. If a provider has no reading
        // for a word, that word stays bare rather than falling back to the source's kana.
        final boolean keepEmbedded = "DEFAULT".equalsIgnoreCase(rubyLanguage)
                && providers.contains(ProcessingProviders.EMBEDDED);
        Lyrics current = formatEmbeddedRuby(lyrics, keepEmbedded, style);
        // NONE: hide all ruby, stop here.
        if ("NONE".equalsIgnoreCase(rubyLanguage)) {
            return current;
        }
        // Automatic stops at the source's own ruby; a manual language also reads the dictionaries.
        if ("DEFAULT".equalsIgnoreCase(rubyLanguage)) {
            return current;
        }
        // Manual language: traverse all providers in order, later ones override earlier ones.
        for (String provider : providers) {
            if (ProcessingProviders.EMBEDDED.equals(provider)) {
                continue; // Already handled by formatEmbeddedRuby above
            }
            if (ProcessingProviders.GOOGLE.equals(provider)) {
                continue; // Network-based; Google returns Latin only, not the target script.
            }
            if (ProcessingProviders.LOCAL.equals(provider)) {
                if ("BOPOMOFO".equalsIgnoreCase(rubyLanguage)) {
                    current = enrichFromBopomofo(current, BopomofoDictStore.word(), style);
                } else if (isJapaneseRubyLanguage(rubyLanguage)) {
                    WordReadingTable table = JapaneseDictStore.table();
                    if (table != null) {
                        current = enrichFromDictionary(current, table, style);
                    }
                } else if ("SIMPLIFIED_CHINESE".equalsIgnoreCase(rubyLanguage)) {
                    current = enrichFromUniDict(current, null, style);
                } else if ("CANTONESE".equalsIgnoreCase(rubyLanguage)) {
                    current = enrichFromUniDict(current, null, style);
                }
                // Custom Rime languages: no pinyin fallback; the RIME provider handles them.
                continue;
            }
            if (ProcessingProviders.RIME.equals(provider)) {
                // BOPOMOFO, Japanese, Simplified Chinese, and Cantonese have their own
                // dictionaries; RIME would override them. Only custom Rime languages use RIME.
                if ("BOPOMOFO".equalsIgnoreCase(rubyLanguage)
                        || isJapaneseRubyLanguage(rubyLanguage)
                        || "SIMPLIFIED_CHINESE".equalsIgnoreCase(rubyLanguage)
                        || "CANTONESE".equalsIgnoreCase(rubyLanguage)) {
                    continue;
                }
                // A custom Rime ruby language: read its own word table, not the romanize language's.
                WordReadingTable table = RimeDictStore.word(rubyLanguage);
                if (table != null) {
                    current = enrichFromRime(current, table, style);
                }
                continue;
            }
            if (ProcessingProviders.ICU.equals(provider)) {
                continue; // Latin-only transliteration; not useful for CJK ruby.
            }
        }
        return current;
    }

    /** Whether {@link #requestAi} has anything to do. */
    public static boolean usesAi(Lyrics lyrics) {
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        if ("NONE".equalsIgnoreCase(rubyLanguage) || "DEFAULT".equalsIgnoreCase(rubyLanguage)) {
            return false;
        }
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
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        if ("NONE".equalsIgnoreCase(rubyLanguage) || "DEFAULT".equalsIgnoreCase(rubyLanguage)) {
            Utils.runOnMainThread(() -> callback.onGenerated(lyrics));
            return;
        }
        final RubyStyle style = rubyStyleFromLanguage(rubyLanguage);
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
                                Settings.LYRICS_AI_ROMANIZATION_PROMPT_2.get(), "furigana",
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

            String reading = rubyReadAs(text, table, null);
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
     * Gives each Chinese character its Bopomofo reading. Every Han character is split into its
     * own word so each one carries its own reading, since bopomofo is character-based and the
     * word table does not hold multi-character entries.
     */
    private static Lyrics enrichFromBopomofo(Lyrics lyrics, @Nullable WordReadingTable table,
                                             RubyStyle style) {
        if (!BopomofoDictStore.isReady()) return lyrics;
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(enrichLineFromBopomofo(line, table, style));
        }
        return lyrics.withLines(lines);
    }

    private static LyricsLine enrichLineFromBopomofo(LyricsLine line, @Nullable WordReadingTable table,
                                                     RubyStyle style) {
        if (!ScriptDetector.contains(line.text(), Character.UnicodeScript.HAN)) {
            return line;
        }
        // Force each Han character into its own word, since bopomofo is character-based.
        List<Word> words = splitHanToSingleChars(segmentedWords(line, table));
        List<Word> read = new ArrayList<>(words.size());
        boolean changed = false;

        int i = 0;
        while (i < words.size()) {
            Word word = words.get(i);
            String text = word.text().trim();
            if (!ScriptDetector.contains(text, Character.UnicodeScript.HAN)) {
                read.add(word);
                i++;
                continue;
            }

            String reading = rubyReadAs(text, null, table);
            if (reading != null) {
                read.add(withRuby(word, formatRuby(reading, style)));
                changed = true;
            } else {
                read.add(word);
            }
            i++;
        }
        return changed ? withWords(line, read) : line;
    }

    /** Splits every Han character into its own word, for the character-based bopomofo reading. */
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
                        null, endsWithSpace, word.ruby()));
                cursorMs = wordEnd;
                i = j;
            }
        }
        return result;
    }

    /**
     * The Bopomofo reading of a word a character at a time, for the words the word table does
     * not have. Characters the character tables do not cover are skipped, so a word that is
     * only partly covered still shows the reading of the characters that are covered.
     */
    @Nullable
    private static String bopomofoCharacterReading(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!ScriptDetector.isHan(c)) {
                continue;
            }
            String reading = UniDictStore.bopomofoReading(c);
            if (reading == null) {
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(reading);
        }
        return out.length() > 0 ? out.toString() : null;
    }

    /**
     * Gives each Han word without a reading its reading from the Rime word table. Words the
     * table does not have are read a character at a time from the character tables, since a
     * character alone still says how it is written.
     */
    private static Lyrics enrichFromRime(Lyrics lyrics, WordReadingTable table,
                                         RubyStyle style) {
        if (!RimeDictStore.isReady(Settings.LYRICS_RUBY_LANGUAGE.get())) return lyrics;
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(enrichLineFromRime(line, table, style));
        }
        return lyrics.withLines(lines);
    }

    private static LyricsLine enrichLineFromRime(LyricsLine line, WordReadingTable table,
                                                 RubyStyle style) {
        if (!ScriptDetector.contains(line.text(), Character.UnicodeScript.HAN)) {
            return line;
        }
        List<Word> words = segmentedWords(line, table);
        // Non-Japanese languages read character by character, so each Han character
        // gets its own word and its own reading.
        if (!isJapaneseRubyLanguage(Settings.LYRICS_RUBY_LANGUAGE.get())) {
            words = splitHanToSingleChars(words);
        }
        List<Word> read = new ArrayList<>(words.size());
        boolean changed = false;

        int i = 0;
        while (i < words.size()) {
            Word word = words.get(i);
            String text = word.text().trim();
            if (!ScriptDetector.contains(text, Character.UnicodeScript.HAN)) {
                read.add(word);
                i++;
                continue;
            }

            String reading = lookupExactReading(text, table);
            if (reading == null) {
                reading = rimeCharacterReading(text);
            }
            if (reading != null) {
                read.add(withRuby(word, formatRuby(reading, style)));
                changed = true;
            } else {
                read.add(word);
            }
            i++;
        }
        return changed ? withWords(line, read) : line;
    }

    /**
     * Gives each Chinese character its pinyin or cantonese reading from the UniDictStore, for the
     * simplified Chinese and cantonese ruby languages. Every Han character is split into its own
     * word so each one carries its own reading, since these languages are character-based.
     */
    private static Lyrics enrichFromUniDict(Lyrics lyrics, @Nullable WordReadingTable table,
                                             RubyStyle style) {
        if (!UniDictStore.isReady()) return lyrics;
        List<LyricsLine> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(enrichLineFromUniDict(line, table, style));
        }
        return lyrics.withLines(lines);
    }

    private static LyricsLine enrichLineFromUniDict(LyricsLine line, @Nullable WordReadingTable table,
                                                     RubyStyle style) {
        if (!ScriptDetector.contains(line.text(), Character.UnicodeScript.HAN)) {
            return line;
        }
        // Force each Han character into its own word, since pinyin/cantonese are character-based.
        List<Word> words = splitHanToSingleChars(segmentedWords(line, table));
        List<Word> read = new ArrayList<>(words.size());
        boolean changed = false;

        int i = 0;
        while (i < words.size()) {
            Word word = words.get(i);
            String text = word.text().trim();
            if (!ScriptDetector.contains(text, Character.UnicodeScript.HAN)) {
                read.add(word);
                i++;
                continue;
            }

            String reading = rubyReadAs(text, null, table);
            if (reading != null) {
                read.add(withRuby(word, formatRuby(reading, style)));
                changed = true;
            } else {
                read.add(word);
            }
            i++;
        }
        return changed ? withWords(line, read) : line;
    }

    /**
     * The Rime reading of a word a character at a time, for the words the word table does
     * not have. Characters the character tables do not cover are skipped, so a word that is
     * only partly covered still shows the reading of the characters that are covered.
     */
    @Nullable
    private static String rimeCharacterReading(String text) {
        StringBuilder out = new StringBuilder();
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!ScriptDetector.isHan(c)) {
                continue;
            }
            String reading = RimeDictStore.reading(c, rubyLanguage);
            if (reading == null) {
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(reading);
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
            if (needsSplit(word.text(), table)) {
                List<Word> parts = segmentText(word.text(), word.startMs(), word.endMs(), table);
                if (parts.size() > 1) {
                    Word last = parts.get(parts.size() - 1);
                    parts.set(parts.size() - 1, new Word(last.startMs(), last.endMs(),
                            last.text(), last.romaji(), word.endsWithSpace(), null));
                    // Carry the word's ruby to the first Han part, so a reading already
                    // applied is not lost when the word is cut up.
                    if (word.ruby() != null) {
                        for (int pi = 0; pi < parts.size(); pi++) {
                            Word part = parts.get(pi);
                            if (ScriptDetector.contains(part.text(), Character.UnicodeScript.HAN)) {
                                parts.set(pi, new Word(part.startMs(), part.endMs(), part.text(),
                                        part.romaji(), part.endsWithSpace(), word.ruby()));
                                break;
                            }
                        }
                    }
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
                String reading = !ScriptDetector.contains(clean, Character.UnicodeScript.HAN)
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

    /** Whether the ruby language is one the shipped Japanese dictionary reads. */
    private static boolean isJapaneseRubyLanguage(String language) {
        return "ja".equalsIgnoreCase(language)
                || "ja-Hira".equalsIgnoreCase(language)
                || "ja-Kana".equalsIgnoreCase(language);
    }

    /** The ruby reading of a word, or null when no local reader covers it. */
    @Nullable
    private static String rubyReadAs(String text,
                                     @Nullable WordReadingTable japaneseTable,
                                     @Nullable WordReadingTable bopomofoTable) {
        final String rubyLanguage = Settings.LYRICS_RUBY_LANGUAGE.get();
        if ("BOPOMOFO".equalsIgnoreCase(rubyLanguage)) {
            String reading = bopomofoTable != null ? lookupExactReading(text, bopomofoTable) : null;
            return reading != null ? reading : bopomofoCharacterReading(text);
        }
        if (isJapaneseRubyLanguage(rubyLanguage)) {
            return japaneseTable != null ? lookupReading(text, japaneseTable) : null;
        }
        // Simplified Chinese, Cantonese, and Traditional Chinese: read each Han character
        // from UniDictStore (pinyin/cantonese) or RimeDictStore (traditional).
        if (rubyLanguage == null) return null;
        if ("TRADITIONAL_CHINESE".equalsIgnoreCase(rubyLanguage)) {
            return rimeCharacterReading(text);
        }
        if ("CANTONESE".equalsIgnoreCase(rubyLanguage)) {
            return chineseCharacterReading(text, "CANTONESE");
        }
        return chineseCharacterReading(text, "SIMPLIFIED_CHINESE");
    }

    /**
     * The pinyin or cantonese reading of a word a character at a time, for the words the word
     * table does not have. Characters the character tables do not cover are skipped, so a word
     * that is only partly covered still shows the reading of the characters that are covered.
     */
    @Nullable
    private static String chineseCharacterReading(String text, String rubyLanguage) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!ScriptDetector.isHan(c)) {
                continue;
            }
            String reading = "CANTONESE".equalsIgnoreCase(rubyLanguage)
                    ? UniDictStore.cantoneseReading(c)
                    : UniDictStore.pinyinReading(c);
            if (reading == null) {
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(reading);
        }
        return out.length() > 0 ? out.toString() : null;
    }

    public enum RubyStyle {
        HIRAGANA,
        KATAKANA
    }

    public interface Callback {
        void onGenerated(Lyrics lyrics);
    }

}
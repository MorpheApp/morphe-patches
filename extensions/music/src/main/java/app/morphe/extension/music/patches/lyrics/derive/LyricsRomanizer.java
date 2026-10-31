/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.romanizers.HangulRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsRomanizer {

    private static final String RULE_BASED_DICTIONARY = "Local (Rule-based)";
    /** A provider reading must cover at least this share of the lyric to be usable. */
    private static final double MIN_READING_COVERAGE = 0.40;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsRomanizer() {
    }

    @Nullable
    public static List<LyricsLine> getEmbeddedRomanization(Lyrics lyrics) {
        if (lyrics == null) return null;
        final boolean allowTransliteration = Settings.LYRICS_ALLOW_TRANSLITERATION.get();
        final int lineCount = lyrics.lines() != null ? lyrics.lines().size() : 0;
        List<LyricsLine> embedded = lyrics.romanization();
        if (embedded != null && embedded.size() != lineCount) {
            embedded = null;
        }
        if (embedded == null || embedded.isEmpty()) {
            Map<String, List<LyricsLine>> romanizations = lyrics.romanizations();
            if (romanizations != null && !romanizations.isEmpty()) {
                embedded = collectMatchingRomanizations(romanizations, lyrics.lines(),
                        LyricsMerge.hasBackgroundTrack(lyrics, romanizations));
            }
        }
        // Kana and hangul are phonetic scripts, so a reading written in them is still a
        // romanization. Other non-Latin readings are transliterations and wait for the gate.
        embedded = normalizeReadingScripts(embedded);
        if (LyricsMerge.hasText(embedded)) {
            return embedded;
        }
        // Otherwise the line reading is built from the readings of its words.
        if (lyrics.lines() != null && LyricsMerge.anyWordHasRomaji(lyrics.lines())) {
            List<LyricsLine> synthesized = new ArrayList<>(lyrics.lines().size());
            int totalWordChars = 0;
            int usableWordChars = 0;
            for (LyricsLine l : lyrics.lines()) {
                if (l.hasWords()) {
                    StringBuilder sb = new StringBuilder();
                    for (Word w : l.words()) {
                        String text = w.text() != null ? w.text().trim() : "";
                        final boolean counted = hasLetterOrDigit(text);
                        if (counted) {
                            totalWordChars += text.length();
                        }
                        String part = wordReading(w, allowTransliteration);
                        part = stripParentheses(part).trim();
                        if (!part.isEmpty() && (allowTransliteration || isPurelyLatinScript(part))) {
                            sb.append(part);
                            if (w.endsWithSpace() && !part.endsWith(" ")) {
                                sb.append(' ');
                            }
                            if (counted) {
                                usableWordChars += text.length();
                            }
                        }
                    }
                    synthesized.add(new LyricsLine(l.startTimeMs(), l.endTimeMs(), sb.toString().trim(),
                            Collections.emptyList(), l.agentId(), l.isDuet(), l.isBG(), l.songPart()));
                } else {
                    synthesized.add(new LyricsLine(l.startTimeMs(), l.endTimeMs(), "",
                            Collections.emptyList(), l.agentId(), l.isDuet(), l.isBG(), l.songPart()));
                }
            }
            // A word track that only covers a few particles is not a reading. Fall back to a
            // whole-line provider instead of showing mostly empty lines.
            if (totalWordChars > 0
                    && (double) usableWordChars / totalWordChars >= MIN_READING_COVERAGE
                    && LyricsMerge.hasText(synthesized)) {
                return synthesized;
            }
        }
        return null;
    }

    /**
     * The text to show for one word's shipped reading. Kana and hangul are romanized, a
     * non-Latin reading is kept only when the user allows transliteration, and a Latin reading
     * is kept unless it looks like a broken fragment.
     */
    public static String wordReading(Word word, boolean allowTransliteration) {
        String reading = word.romaji();
        if (reading != null && !reading.trim().isEmpty()) {
            reading = reading.trim();
            // A tilde is only an artifact when the written word does not have one.
            if (!isDegradedRomajiToken(reading, containsTilde(word.text()))) {
                if (ScriptDetector.contains(reading, Character.UnicodeScript.HAN)) {
                    if (allowTransliteration) {
                        return reading;
                    }
                    // Not allowed: fall through to the word itself, which may still have
                    // kana or hangul that can be romanized.
                } else if (ScriptDetector.contains(reading, Character.UnicodeScript.HANGUL)) {
                    reading = HangulRomanizer.romanize(reading).trim();
                } else if (ScriptDetector.containsKana(reading)) {
                    reading = KanaRomanizer.romanize(reading).trim();
                }
                if (allowTransliteration || isPurelyLatinScript(reading)) {
                    return reading;
                }
            }
        }
        // Fall back to the word itself when it is a phonetic script with no reading.
        String original = word.text();
        if (original != null && !ScriptDetector.contains(original, Character.UnicodeScript.HAN)) {
            String fallback = null;
            if (ScriptDetector.contains(original, Character.UnicodeScript.HANGUL)) {
                fallback = HangulRomanizer.romanize(original).trim();
            } else if (ScriptDetector.containsKana(original)) {
                fallback = KanaRomanizer.romanize(original).trim();
            }
            if (fallback != null && (allowTransliteration || isPurelyLatinScript(fallback))) {
                return fallback;
            }
        }
        return "";
    }

    /**
     * Romanizes provider readings written in kana or hangul. Only a line made up entirely of
     * that phonetic script is converted, so mixed text is never left half romanized.
     */
    @Nullable
    private static List<LyricsLine> normalizeReadingScripts(@Nullable List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<LyricsLine> out = null;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            String text = line.text();
            if (text == null || text.isEmpty()) {
                continue;
            }
            String converted = text;
            if (isHangulOnly(text)) {
                String romanized = HangulRomanizer.romanize(text);
                if (!ScriptDetector.contains(romanized, Character.UnicodeScript.HANGUL)) {
                    converted = romanized;
                }
            } else if (isKanaOnly(text)) {
                String romanized = KanaRomanizer.romanize(text);
                if (!ScriptDetector.containsKana(romanized)) {
                    converted = romanized;
                }
            }
            if (!converted.equals(text)) {
                if (out == null) {
                    out = new ArrayList<>(lines);
                }
                // Word timings belong to the original line, so the converted reading keeps
                // only the line's own timing.
                out.set(i, new LyricsLine(line.startTimeMs(), line.endTimeMs(), converted,
                        Collections.emptyList(), line.agentId(), line.isDuet(), line.isBG(),
                        line.songPart()));
            }
        }
        return out != null ? out : lines;
    }

    private static boolean isKanaOnly(String text) {
        boolean hasKana = false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                hasKana = true;
                continue;
            }
            if (cp == 0x30FC || cp == 0x2014) {
                continue; // Prolonged sound mark and dash.
            }
            if (Character.isLetter(cp)) {
                return false;
            }
        }
        return hasKana;
    }

    private static boolean isHangulOnly(String text) {
        boolean hasHangul = false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL) {
                hasHangul = true;
                continue;
            }
            if (Character.isLetter(cp)) {
                return false;
            }
        }
        return hasHangul;
    }

    /** Whether any word ships a reading that the current setting allows. */
    private static boolean hasUsableWordReading(@Nullable List<LyricsLine> lines,
                                                boolean allowTransliteration) {
        if (lines == null) {
            return false;
        }
        for (LyricsLine line : lines) {
            for (Word word : line.words()) {
                if (!wordReading(word, allowTransliteration).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether one line's word readings supply an allowed reading. */
    private static boolean hasUsableWordReadingForLine(LyricsLine line,
                                                       @Nullable LyricsLine original,
                                                       boolean allowTransliteration) {
        List<Word> words = line.words().isEmpty() && original != null
                ? original.words() : line.words();
        for (Word word : words) {
            if (!wordReading(word, allowTransliteration).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a token has anything worth measuring, rather than only punctuation. */
    private static boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTilde(@Nullable String text) {
        return text != null && (text.contains("~") || text.contains("～"));
    }

    public static void romanize(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        boolean anyLineHasWords = false;
        for (LyricsLine l : lyrics.lines()) {
            if (l.hasWords()) {
                anyLineHasWords = true;
                break;
            }
        }
        final boolean isWordSynced = anyLineHasWords;

        // The source can include its own reading; it is just another provider in the list.
        final boolean allowTransliteration = Settings.LYRICS_ALLOW_TRANSLITERATION.get();
        final List<String> providers = AiProviders.enabledProviders(
                Settings.LYRICS_ROMANIZATION_PROVIDERS.get(),
                AiProviders.ROMANIZATION_ORDER);
        if (providers.isEmpty()) {
            callback.onRomanized(null, false, false, null, false, null);
            return;
        }

        final boolean sourceEnabled = providers.contains(AiProviders.EMBEDDED);
        final boolean perWord = sourceEnabled && LyricsMerge.anyWordHasRomaji(lyrics.lines());
        final ProviderResult embedded = sourceEnabled
                ? embeddedRomanization(lyrics, isWordSynced, perWord, allowTransliteration)
                : null;

        // Embedded readings are already in memory, so keep the old synchronous result.
        if (AiProviders.EMBEDDED.equals(providers.get(0)) && embedded != null) {
            List<LyricsLine> result = embedded.lines();
            boolean hasWordRomaji = (perWord
                    && hasUsableWordReading(lyrics.lines(), allowTransliteration))
                    || (isWordSynced && hasUsableWordReading(result, allowTransliteration));
            callback.onRomanized(result, false, false, null, hasWordRomaji, null);
            return;
        }

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(stripParentheses(line.text()));
        }

        executor.execute(() -> {
            try {
                for (String provider : providers) {
                    try {
                        final boolean fromSource = AiProviders.EMBEDDED.equals(provider);
                        ProviderResult result = fromSource
                                ? embedded
                                : romanizeWithProvider(provider, track, lyrics, source, lines,
                                isWordSynced, allowTransliteration);
                        if (result == null || !LyricsMerge.hasText(result.lines())) {
                            continue;
                        }
                        if (!fromSource && !hasUsableRomanization(
                                result.lines(), lyrics, allowTransliteration)) {
                            continue;
                        }

                        final List<LyricsLine> finalResult = result.lines();
                        final boolean hasWordRomaji = (isWordSynced
                                && hasUsableWordReading(finalResult, allowTransliteration))
                                || (fromSource && perWord && hasUsableWordReading(
                                lyrics.lines(), allowTransliteration));
                        Utils.runOnMainThread(() -> callback.onRomanized(
                                finalResult, result.fromGoogle(), result.fromAI(), result.model(),
                                hasWordRomaji, result.localDictionary()));
                        return;
                    } catch (Exception ex) {
                        Logger.printInfo(() -> "romanization provider failed: " + provider, ex);
                    }
                }

                Utils.runOnMainThread(
                        () -> callback.onRomanized(null, false, false, null, false, null));
            } catch (Exception ex) {
                Logger.printInfo(() -> "romanize failure", ex);
                Utils.runOnMainThread(
                        () -> callback.onRomanized(null, false, false, null, false, null));
            }
        });
    }

    /** Null when the lyrics source has no usable reading of its own. */
    @Nullable
    private static ProviderResult embeddedRomanization(Lyrics lyrics, boolean isWordSynced,
                                                       boolean perWord, boolean allowTransliteration) {
        List<LyricsLine> embedded = getEmbeddedRomanization(lyrics);
        if (!LyricsMerge.hasText(embedded)
                || isProviderRomajiDegraded(embedded, lyrics.lines(), perWord,
                allowTransliteration)) {
            return null;
        }
        return new ProviderResult(alignIfWordSynced(lyrics, embedded, isWordSynced),
                false, false, null, null);
    }

    @Nullable
    private static ProviderResult romanizeWithProvider(
            String provider, TrackInfo track, Lyrics lyrics, String source,
            List<String> lines, boolean isWordSynced, boolean allowTransliteration) {
        if (AiProviders.LOCAL.equals(provider)) {
            return romanizeLocal(lyrics, lines, isWordSynced);
        }
        if (AiProviders.RIME.equals(provider)) {
            return romanizeRime(lyrics, lines, isWordSynced);
        }
        if (AiProviders.isAi(provider)) {
            return romanizeAi(provider, track, lyrics, source, lines, isWordSynced,
                    allowTransliteration);
        }
        if (AiProviders.GOOGLE.equals(provider)) {
            return romanizeGoogle(track, lyrics, source, lines, isWordSynced,
                    allowTransliteration);
        }
        return null;
    }

    /**
     * The Rime Wordlist provider: the Han characters of the language are read by its word table, the
     * scripts around them by the same local dictionaries. Nothing is read when the language has no
     * word table, so the next provider is given the turn instead.
     */
    @Nullable
    private static ProviderResult romanizeRime(Lyrics lyrics, List<String> lines,
                                               boolean isWordSynced) {
        if (!RimeDictStore.isActive()) {
            return null;
        }
        // Fetching happens only when the row is turned on or a choice is saved, never here.
        if (!RimeDictStore.isReady()) {
            return null;
        }

        LocalRomanizer.Result local = LocalRomanizer.romanizeRime(lines);
        List<LyricsLine> result = local != null ? toLines(local.lines) : null;
        String dictionary = local != null ? local.dictionary : null;
        if (!LyricsMerge.hasText(result)) {
            return null;
        }
        List<LyricsLine> aligned = alignIfWordSynced(lyrics, result, isWordSynced);
        return new ProviderResult(aligned, false, false, null, dictionary);
    }

    @Nullable
    private static ProviderResult romanizeLocal(Lyrics lyrics, List<String> lines,
                                                boolean isWordSynced) {
        // The Han characters of a language with a Rime word table are the Rime Wordlist provider's to
        // read. Reading them here as well would give the line the Mandarin reading, and since
        // this provider usually comes first the word table would never be used at all. The whole
        // lyric is left to that provider, which reads the scripts around the Han the same way.
        if (rimeMapEnabled() && RimeDictStore.isActive()) {
            return null;
        }

        // Reading is rule-based until the dictionaries the user asked for are on disk; fetching
        // happens only when the row is turned on or a choice is saved, never from playback. A
        // store that is running keeps feeding readings in as it goes.
        LocalRomanizer.Result local = LocalRomanizer.romanize(lines);
        List<LyricsLine> result = local != null ? toLines(local.lines) : null;
        String dictionary = local != null ? local.dictionary : null;
        if (!LyricsMerge.hasText(result)) {
            result = ruleBased(lines);
            dictionary = result != null ? RULE_BASED_DICTIONARY : null;
        }
        if (!LyricsMerge.hasText(result)) {
            return null;
        }
        List<LyricsLine> aligned = alignIfWordSynced(lyrics, result, isWordSynced);
        return new ProviderResult(aligned, false, false, null, dictionary);
    }

    /** Whether the Rime Wordlist provider is switched on in the stored list. */
    private static boolean rimeMapEnabled() {
        return AiProviders.enabledProviders(Settings.LYRICS_ROMANIZATION_PROVIDERS.get(),
                AiProviders.ROMANIZATION_ORDER).contains(AiProviders.RIME);
    }

    @Nullable
    private static ProviderResult romanizeAi(String provider, TrackInfo track, Lyrics lyrics,
                                             String source, List<String> lines,
                                             boolean isWordSynced, boolean allowTransliteration) {
        List<LyricsLine> aiCached = LyricsCache.getRomanizationAI(
                track, source, lines, provider);
        if (hasUsableRomanization(aiCached, lyrics, allowTransliteration)) {
            // The cache key includes the model that produced it.
            return new ProviderResult(
                    alignIfWordSynced(lyrics, aiCached, isWordSynced),
                    false, true, Settings.LYRICS_AI_MODEL.get(), null);
        }

        String prompt = OpenAIClient.renderPrompt(
                Settings.LYRICS_AI_ROMANIZATION_PROMPT.get(), "romanization",
                LyricsRequests.romanizationLanguage(), track.title(), track.artist(), lines);
        AiProviders.Result ai = AiProviders.mapLines(provider, prompt, lines);
        if (ai == null || ai.lines() == null || ai.lines().isEmpty()) {
            return null;
        }

        List<LyricsLine> aiRaw = toLines(ai.lines());
        if (lyrics.lines() == null || aiRaw.size() != lyrics.lines().size()) {
            // Reject short answers before word-sync padding, so the next provider can try.
            return null;
        }
        List<LyricsLine> aiLines = alignIfWordSynced(lyrics, aiRaw, isWordSynced);
        if (!hasUsableRomanization(aiLines, lyrics, allowTransliteration)) {
            return null;
        }
        LyricsCache.putRomanizationAI(track, source, lines, aiLines, provider);
        return new ProviderResult(aiLines, false, true, ai.model(), null);
    }

    @Nullable
    private static ProviderResult romanizeGoogle(TrackInfo track, Lyrics lyrics, String source,
                                                 List<String> lines, boolean isWordSynced,
                                                 boolean allowTransliteration) {
        List<LyricsLine> result;
        if (isWordSynced) {
            result = romanizeWordSynced(lyrics);
            if (result == null) {
                return null;
            }
        } else {
            result = LyricsCache.getRomanization(track, source, lines);
            if (result == null || !hasUsableRomanization(result, lyrics, allowTransliteration)) {
                result = romanizeLineSynced(lyrics);
                if (result == null) {
                    return null;
                }
                if (hasUsableRomanization(result, lyrics, allowTransliteration)) {
                    LyricsCache.putRomanization(track, source, lines, result);
                }
            }
        }

        if (!hasUsableRomanization(result, lyrics, allowTransliteration)) {
            return null;
        }
        List<LyricsLine> aligned = alignIfWordSynced(lyrics, result, isWordSynced);
        return new ProviderResult(aligned, true, false, null, null);
    }

    /**
     * Rejects a reading that is only the original line, which older versions could store after
     * a failed online request. With transliteration off, a non-Latin line is usable only when
     * word-synced readings replace it with Latin text.
     */
    private static boolean hasUsableRomanization(@Nullable List<LyricsLine> result, Lyrics lyrics,
                                                 boolean allowTransliteration) {
        if (!LyricsMerge.hasText(result)) {
            return false;
        }
        List<LyricsLine> original = lyrics.lines();
        if (original == null) {
            return true;
        }
        if (result.size() != original.size()) {
            return false;
        }
        if (!allowTransliteration) {
            for (int i = 0; i < result.size(); i++) {
                String reading = result.get(i).text();
                reading = reading != null ? reading.trim() : "";
                if (reading.isEmpty() || isPurelyLatinScript(reading)) {
                    continue;
                }
                // Word-synced requests can leave the line text unchanged when only the syllable
                // request succeeded. The per-word readings are still a usable romanization.
                boolean usable = false;
                if (original.get(i).hasWords() && !result.get(i).words().isEmpty()) {
                    for (Word word : result.get(i).words()) {
                        String romaji = wordReading(word, false);
                        String written = word.text() == null ? "" : word.text().trim();
                        if (!romaji.isEmpty() && !romaji.equals(written)
                                && isPurelyLatinScript(romaji)) {
                            usable = true;
                            break;
                        }
                    }
                }
                if (!usable) {
                    return false;
                }
            }
            return true;
        }
        boolean nonLatin = false;
        for (int i = 0; i < result.size(); i++) {
            String source = original.get(i).text();
            if (source == null || source.isEmpty()) {
                continue;
            }
            // Compare against the same text the online request sends, so an outer parenthetical
            // wrapper does not look like a reading.
            source = stripParentheses(source).trim();
            if (source.isEmpty() || isPurelyLatinScript(source)) {
                continue;
            }
            nonLatin = true;
            String reading = result.get(i).text();
            if (reading != null) {
                reading = reading.trim();
                if (!reading.isEmpty() && !reading.equals(source)) {
                    return true;
                }
            }
            // Word-synced requests can leave the line text unchanged when only the syllable
            // request succeeded. The per-word readings are still a usable romanization.
            for (Word word : result.get(i).words()) {
                String romaji = wordReading(word, allowTransliteration);
                String written = word.text() == null ? "" : word.text().trim();
                if (!romaji.isEmpty() && !romaji.equals(written)) {
                    return true;
                }
            }
        }
        return !nonLatin;
    }

    /**
     * Romanizes kana and hangul by rule, with no dictionary, for when nothing better is
     * available. Null if no line has either.
     */
    @Nullable
    private static List<LyricsLine> ruleBased(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        boolean applied = false;
        for (String line : lines) {
            String rom = line;
            if (ScriptDetector.containsKana(line)) {
                rom = KanaRomanizer.romanize(line);
            } else if (ScriptDetector.contains(line, Character.UnicodeScript.HANGUL)) {
                rom = HangulRomanizer.romanize(line);
            }
            applied |= !rom.equals(line);
            result.add(rom);
        }
        return applied ? toLines(result) : null;
    }

    @Nullable
    private static List<LyricsLine> alignIfWordSynced(Lyrics lyrics, @Nullable List<LyricsLine> lines,
                                                      boolean isWordSynced) {
        return isWordSynced && lines != null ? alignLinesToWordSync(lyrics, lines) : lines;
    }

    @Nullable
    private static List<LyricsLine> romanizeWordSynced(Lyrics lyrics) {
        if (lyrics.lines() == null) {
            return Collections.emptyList();
        }

        List<LyricsLine> origLines = lyrics.lines();
        List<String> allLines = new ArrayList<>(origLines.size());
        List<String> allSyllables = new ArrayList<>();
        int[] lineStructure = new int[origLines.size()];

        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine line = origLines.get(i);
            String text = line.text() != null ? stripParentheses(line.text()).trim() : "";
            allLines.add(text);
            List<Word> words = line.words();
            lineStructure[i] = words.size();
            for (Word w : words) {
                allSyllables.add(w.text() != null ? w.text() : "");
            }
        }

        TextRomanization full = romanizeTexts(allLines);
        TextRomanization isolated = !allSyllables.isEmpty()
                ? romanizeTexts(allSyllables)
                : new TextRomanization(Collections.emptyList(), true);
        final boolean useFull = full.complete();
        final boolean useIsolated = isolated.complete();
        if (!useFull && !useIsolated) {
            // Both requests failed. Tell the caller so the next provider can run instead of
            // passing the original script off as a reading.
            return null;
        }

        List<String> fullLineResults = full.lines();
        List<String> isolatedResults = isolated.lines();

        List<LyricsLine> resultLines = new ArrayList<>(origLines.size());
        int globalSyllableIndex = 0;

        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine line = origLines.get(i);
            String fullLineRom = useFull
                    && i < fullLineResults.size() && fullLineResults.get(i) != null
                    ? fullLineResults.get(i) : allLines.get(i);
            int count = lineStructure[i];
            List<Word> origWords = line.words();

            if (count == 0 || !line.hasWords()) {
                resultLines.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(),
                        fullLineRom, Collections.emptyList(), line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
                continue;
            }

            int endSyllableIndex = useIsolated
                    ? Math.min(globalSyllableIndex + count, isolatedResults.size())
                    : globalSyllableIndex;
            List<String> romanizedGuides = useIsolated && globalSyllableIndex < isolatedResults.size()
                    ? new ArrayList<>(isolatedResults.subList(globalSyllableIndex, endSyllableIndex))
                    : new ArrayList<>();
            while (romanizedGuides.size() < count) {
                romanizedGuides.add("");
            }

            List<String> originalSyllables = new ArrayList<>(count);
            for (Word w : origWords) {
                originalSyllables.add(w.text() != null ? w.text() : "");
            }

            globalSyllableIndex += count;

            List<String> alignedChunks = alignRomanizationAnchors(fullLineRom, romanizedGuides, originalSyllables);

            List<Word> romanizedWords = new ArrayList<>(count);
            StringBuilder lineRomBuilder = new StringBuilder();

            for (int wIdx = 0; wIdx < count; wIdx++) {
                Word w = origWords.get(wIdx);
                String rom = (wIdx < alignedChunks.size() && alignedChunks.get(wIdx) != null) ? alignedChunks.get(wIdx) : "";
                romanizedWords.add(new Word(w.startMs(), w.endMs(), w.text(), rom, w.endsWithSpace()));
                lineRomBuilder.append(rom);
            }

            String finalLineRom = !fullLineRom.isEmpty() ? fullLineRom : lineRomBuilder.toString().trim();

            resultLines.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(),
                    finalLineRom, romanizedWords, line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
        }

        return resultLines;
    }

    @Nullable
    private static List<LyricsLine> romanizeLineSynced(Lyrics lyrics) {
        if (lyrics.lines() == null) {
            return Collections.emptyList();
        }

        List<LyricsLine> origLines = lyrics.lines();
        List<String> texts = new ArrayList<>(origLines.size());
        for (LyricsLine l : origLines) {
            String text = l.text();
            texts.add(text != null ? stripParentheses(text).trim() : "");
        }

        TextRomanization romanizedResults = romanizeTexts(texts);
        if (!romanizedResults.complete()) {
            return null;
        }
        List<String> fetched = romanizedResults.lines();

        List<LyricsLine> result = new ArrayList<>(origLines.size());
        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine origLine = origLines.get(i);
            String origText = texts.get(i);
            String roma = (i < fetched.size() && fetched.get(i) != null)
                    ? fetched.get(i) : origText;
            if (roma.isEmpty()) {
                roma = origText;
            }

            result.add(new LyricsLine(origLine.startTimeMs(), origLine.endTimeMs(),
                    roma, Collections.emptyList(), origLine.agentId(), origLine.isDuet(), origLine.isBG(), origLine.songPart()));
        }
        return result;
    }

    /** Romanizes non-Latin lines; an incomplete result lets the caller try the next provider. */
    private static TextRomanization romanizeTexts(List<String> texts) {
        List<Integer> indices = new ArrayList<>();
        List<String> toFetch = new ArrayList<>();
        List<String> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i) != null ? texts.get(i) : "";
            result.add(text);
            if (!isPurelyLatinScript(text)) {
                indices.add(i);
                toFetch.add(text);
            }
        }
        if (toFetch.isEmpty()) {
            return new TextRomanization(result, true);
        }

        final String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        List<String> fetched = LyricsMerge.mapLinesOnline(toFetch, batch -> {
            try {
                return TextTranslator.romanize(batch, language);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        if (fetched == null || fetched.size() != indices.size()) {
            return new TextRomanization(result, false);
        }
        for (int k = 0; k < indices.size(); k++) {
            result.set(indices.get(k), fetched.get(k));
        }
        return new TextRomanization(result, true);
    }

    private static List<LyricsLine> collectMatchingRomanizations(
            Map<String, List<LyricsLine>> romanizations, List<LyricsLine> allLines,
            boolean fallbackToSungLine) {
        // Keys are xml:lang from the file, with "romaji" standing in for a sidecar that named none.
        String langTag = LyricsRequests.isDefaultRomanizationLanguage()
                ? Locale.getDefault().toLanguageTag()
                : LyricsRequests.romanizationLanguage();
        String langCode = LyricsRequests.primarySubtag(langTag);

        List<String> matchedKeys = new ArrayList<>();
        for (String key : romanizations.keySet()) {
            if (key.startsWith(LyricsMerge.BACKGROUND_TRACK_PREFIX)) continue;
            if (key.equals(langTag) || key.equals(langCode)
                    || key.startsWith(langCode + "-") || key.startsWith(langCode + "_")) {
                matchedKeys.add(key);
            }
        }

        if (matchedKeys.isEmpty()) {
            for (String key : romanizations.keySet()) {
                if (!key.startsWith(LyricsMerge.BACKGROUND_TRACK_PREFIX)) {
                    matchedKeys.add(key);
                }
            }
        }

        if (matchedKeys.isEmpty()) {
            return null;
        }

        final int lineCount;
        {
            List<LyricsLine> first = romanizations.get(matchedKeys.get(0));
            lineCount = (first != null) ? first.size() : 0;
        }
        if (lineCount == 0) {
            return null;
        }

        List<LyricsLine> result = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            StringBuilder merged = new StringBuilder();
            for (String key : matchedKeys) {
                List<LyricsLine> langLines = romanizations.get(key);
                if (langLines == null || i >= langLines.size()) continue;
                String text = langLines.get(i).text();
                if (text == null) continue;
                text = stripParentheses(text).trim();
                if (!text.isEmpty()) {
                    //noinspection SizeReplaceableByIsEmpty
                    if (merged.length() > 0) merged.append('\n');
                    merged.append(text);
                }
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, merged.toString()));
        }

        if (allLines != null) {
            for (int i = 0; i < result.size() && i < allLines.size(); i++) {
                if (!allLines.get(i).isBG()) {
                    continue;
                }
                String own = "";
                for (String key : matchedKeys) {
                    own = LyricsMerge.textAt(
                            LyricsMerge.backgroundTrack(romanizations, key), i);
                    if (!own.isEmpty()) {
                        break;
                    }
                }
                if (!own.isEmpty()) {
                    result.set(i, new LyricsLine(LyricsLine.NO_TIME, own));
                    continue;
                }
                if (!fallbackToSungLine) {
                    continue;
                }
                String bgRoma = result.get(i).text();
                if (bgRoma == null || bgRoma.isEmpty()) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (!allLines.get(j).isBG() && j < result.size()) {
                            String parentRoma = result.get(j).text();
                            if (parentRoma != null && !parentRoma.isEmpty()) {
                                result.set(i, new LyricsLine(LyricsLine.NO_TIME, parentRoma));
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (allLines == null || result.size() != allLines.size()) {
            return null;
        }
        return result;
    }

    private static String stripParentheses(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        boolean stripped = true;
        while (stripped && trimmed.length() >= 2) {
            stripped = false;
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '(' && last == ')')
                    || (first == '（' && last == '）')
                    || (first == '[' && last == ']')
                    || (first == '【' && last == '】')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
                stripped = true;
            }
        }
        return trimmed;
    }

    private static boolean isMandarinPinyinToken(@Nullable String t) {
        if (t == null || t.isEmpty()) return false;
        String s = t.trim().toLowerCase(Locale.ROOT);
        if (s.contains(" ")) {
            for (String part : s.split("\\s+")) {
                if (isMandarinPinyinToken(part)) return true;
            }
            return false;
        }
        if (s.startsWith("zh") || s.startsWith("x") || s.startsWith("q")) {
            return true;
        }
        if (s.endsWith("ng") && !s.equals("ng")) {
            return true; // e.g. chang, zeng, zhang, zhong, etc.
        }
        if (s.endsWith("ian") || s.endsWith("iao") || s.endsWith("uang") || s.endsWith("iang")
                || s.endsWith("ong") || s.endsWith("eng") || s.endsWith("ang") || s.endsWith("uan")
                || s.endsWith("iong")) {
            return true;
        }
        return s.equals("ceng") || s.equals("cuan") || s.equals("zuan") || s.equals("zhe")
                || s.equals("dong") || s.equals("sheng") || s.equals("chuang")
                || s.equals("xian") || s.equals("qian") || s.equals("tian") || s.equals("lian")
                || s.equals("jian") || s.equals("bian") || s.equals("pian") || s.equals("mian")
                || s.equals("dian") || s.equals("nian") || s.equals("huan") || s.equals("guan")
                || s.equals("kuan") || s.equals("duan") || s.equals("tuan") || s.equals("luan")
                || s.equals("ruan") || s.equals("xuan") || s.equals("quan") || s.equals("juan")
                || s.equals("yang") || s.equals("ying") || s.equals("yong") || s.equals("wang")
                || s.equals("weng") || s.equals("xi");
    }

    /**
     * @param allowTilde Whether the written word already has a tilde. A tilde in the reading
     *                   is then part of the lyric, not a broken mora marker.
     */
    private static boolean isDegradedRomajiToken(@Nullable String t, boolean allowTilde) {
        if (t == null || t.isEmpty()) return false;
        String s = t.trim().toLowerCase(Locale.ROOT);
        if (s.contains(" ")) {
            for (String part : s.split("\\s+")) {
                if (isDegradedRomajiToken(part, allowTilde)) return true;
            }
            return false;
        }
        // A counter reading left with its digit, such as 1-ri, 1-tsu or 2nin.
        if (s.matches("^\\d+-[a-z]+$") || s.matches("^\\d+[a-z]+$")) {
            return true;
        }
        // A sokuon written as a symbol, such as ~tsu or -tsu.
        if (s.contains("-tsu")) {
            return true;
        }
        if (!allowTilde && (s.contains("~tsu") || s.contains("～tsu")
                || s.endsWith("~") || s.startsWith("~") || s.contains("～") || s.contains("~"))) {
            return true;
        }
        // A symbol or punctuation mark on its own.
        if (s.equals("-") || s.equals("^") || s.equals("_")
                || (!allowTilde && (s.equals("~") || s.equals("～")))) {
            return true;
        }
        // Pinyin is checked at the track level, where a majority of syllables is needed. A
        // single token is not enough evidence and would reject valid Japanese readings.

        // A doubled consonant on its own, which is a sokuon split from its mora.
        return s.equals("pp") || s.equals("tt") || s.equals("kk") || s.equals("ss")
                || s.equals("cc") || s.equals("dd") || s.equals("bb") || s.equals("ff")
                || s.equals("gg") || s.equals("hh") || s.equals("jj") || s.equals("mm")
                || s.equals("rr") || s.equals("ww") || s.equals("yy") || s.equals("zz");
    }

    /**
     * Whether a reading shipped by the provider is too broken to use.
     *
     * <p>Checks that say the data itself is wrong, such as mora fragments, stray symbols or
     * missing lines, always apply. With transliteration off, a non-Latin reading passes only
     * if its word readings supply the text.
     */
    public static boolean isProviderRomajiDegraded(@Nullable List<LyricsLine> embedded,
                                                   @Nullable List<LyricsLine> originalLines,
                                                   boolean perWord,
                                                   boolean allowTransliteration) {
        if (!perWord && (embedded == null || embedded.isEmpty())) {
            return true;
        }

        int totalTokens = 0;
        int totalChars = 0;
        int totalChecked = 0;
        int allSingleLetterTokens = 0;
        int japaneseLatinTokens = 0;
        int pinyinTokens = 0;

        if (embedded != null && !embedded.isEmpty()) {
            for (int li = 0; li < embedded.size(); li++) {
                LyricsLine line = embedded.get(li);
                String text = line.text();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }

                LyricsLine origLine = (originalLines != null && li < originalLines.size()) ? originalLines.get(li) : null;
                if (!allowTransliteration && !isPurelyLatinScript(text)
                        && (!perWord || !hasUsableWordReadingForLine(line, origLine, false))) {
                    return true;
                }
                totalChecked++;

                String origText = origLine != null ? origLine.text() : null;
                final boolean japaneseSource = ScriptDetector.containsKana(origText);

                // A pure kana or hangul reading was romanized above. Either script left in
                // the line means the provider sent mixed or unreadable text rather than a
                // reading.
                if (ScriptDetector.containsKana(text)
                        || ScriptDetector.contains(text, Character.UnicodeScript.HANGUL)) {
                    return true;
                }

                // A tilde the lyric does not have is an artifact of the reading.
                for (int ci = 0; ci < text.length(); ci++) {
                    char c = text.charAt(ci);
                    if (c == '~' || c == '～') {
                        if (origText == null || (!origText.contains("~") && !origText.contains("～"))) {
                            return true; // symbol artifact like 3 ~ 6
                        }
                    }
                }

                int singleLetterTokens = 0;
                String[] tokens = text.trim().split("\\s+");
                for (String t : tokens) {
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (isDegradedRomajiToken(t, containsTilde(origText))) {
                        return true; // trigger for pp 1-ri etc
                    }
                    // The length checks below were written for Latin readings. A Chinese
                    // character is one UTF-16 unit and `Character.isLetter`, so counting it
                    // would reject every transliteration as a line of single letters.
                    if (ScriptDetector.containsCjk(t)) {
                        continue;
                    }
                    if (!hasLetterOrDigit(t)) {
                        continue;
                    }
                    totalTokens++;
                    totalChars += t.length();
                    if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                        singleLetterTokens++;
                        allSingleLetterTokens++;
                    }
                    if (japaneseSource && ScriptDetector.contains(t, Character.UnicodeScript.LATIN)) {
                        japaneseLatinTokens++;
                        if (isMandarinPinyinToken(t)) {
                            pinyinTokens++;
                        }
                    }
                }
                // Several lone letters mean the morae were split apart.
                if (singleLetterTokens >= 3 && japaneseSource) {
                    return true;
                }
            }
            if (totalChecked == 0) return true;
            // A pinyin reading against Japanese lyrics is the wrong song. Two syllables are
            // required so that one English word ending in "ng" cannot trigger it.
            if (pinyinTokens >= 2 && pinyinTokens * 2 >= japaneseLatinTokens) {
                return true;
            }
            if (originalLines != null) {
                int origNonEmpty = 0;
                for (LyricsLine l : originalLines) {
                    if (l.text() != null && !l.text().trim().isEmpty()) {
                        origNonEmpty++;
                    }
                }
                if (origNonEmpty > 0
                        && (double) totalChecked / origNonEmpty < MIN_READING_COVERAGE) {
                    return true;
                }
            }
        }

        // The per word readings are checked the same way.
        if (perWord && originalLines != null) {
            int totalWordTokens = 0;
            int charWordTokens = 0;
            int allSingleWordTokens = 0;
            int japaneseWordLatinTokens = 0;
            int pinyinWordTokens = 0;
            for (LyricsLine l : originalLines) {
                if (!l.hasWords()) continue;
                String origLineText = l.text();
                final boolean japaneseSource = ScriptDetector.containsKana(origLineText);
                int singleWordTokens = 0;
                for (Word w : l.words()) {
                    String r = w.romaji();
                    if (r != null && !r.trim().isEmpty()) {
                        String origWord = w.text();
                        for (int ci = 0; ci < r.length(); ci++) {
                            char c = r.charAt(ci);
                            if (c == '~' || c == '～') {
                                if (origWord == null || (!origWord.contains("~") && !origWord.contains("～"))) {
                                    return true;
                                }
                            }
                        }

                        String[] tokens = r.trim().split("\\s+");
                        for (String t : tokens) {
                            if (t.isEmpty()) {
                                continue;
                            }
                            if (isDegradedRomajiToken(t, containsTilde(origWord))) {
                                return true; // trigger for 1-ri pp etc
                            }
                            if (ScriptDetector.containsCjk(t)) {
                                continue;
                            }
                            if (!hasLetterOrDigit(t)) {
                                continue;
                            }
                            totalWordTokens++;
                            charWordTokens += t.length();
                            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                                singleWordTokens++;
                                allSingleWordTokens++;
                            }
                            if (japaneseSource && ScriptDetector.contains(t, Character.UnicodeScript.LATIN)) {
                                japaneseWordLatinTokens++;
                                if (isMandarinPinyinToken(t)) {
                                    pinyinWordTokens++;
                                }
                            }
                        }
                    }
                }
                if (singleWordTokens >= 3 && japaneseSource) {
                    return true;
                }
            }
            if (pinyinWordTokens >= 2 && pinyinWordTokens * 2 >= japaneseWordLatinTokens) {
                return true;
            }
            if (totalWordTokens >= 2) {
                double avgWordLen = (double) charWordTokens / totalWordTokens;
                double singleLetterRatio = (double) allSingleWordTokens / totalWordTokens;
                if (avgWordLen < 1.4 || singleLetterRatio > 0.5) {
                    return true;
                }
            }
        }

        // Readings made of single letter tokens are mora fragments, not words.
        if (totalTokens >= 2) {
            double avgLen = (double) totalChars / totalTokens;
            double singleLetterRatio = (double) allSingleLetterTokens / totalTokens;
            return avgLen < 1.4 || singleLetterRatio > 0.5;
        }

        return false;
    }

    private static List<LyricsLine> alignLinesToWordSync(@Nullable Lyrics lyrics, @Nullable List<LyricsLine> rLines) {
        if (lyrics == null || lyrics.lines() == null || rLines == null) {
            return rLines;
        }
        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> out = new ArrayList<>(origLines.size());
        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine orig = origLines.get(i);
            LyricsLine rLine = (i < rLines.size()) ? rLines.get(i) : null;
            if (rLine == null) {
                out.add(new LyricsLine(orig.startTimeMs(), ""));
                continue;
            }
            if (!orig.hasWords() || (rLine.hasWords() && rLine.words().size() == orig.words().size())) {
                out.add(rLine);
                continue;
            }
            String fullRoma = rLine.text() != null ? rLine.text().trim() : "";
            if (fullRoma.isEmpty()) {
                out.add(rLine);
                continue;
            }
            List<Word> origWords = orig.words();
            List<String> alignedChunks = alignRomanizationAnchors(origWords, fullRoma);
            List<Word> rWords = new ArrayList<>(origWords.size());
            for (int w = 0; w < origWords.size(); w++) {
                Word ow = origWords.get(w);
                String chunk = (w < alignedChunks.size()) ? alignedChunks.get(w) : "";
                rWords.add(new Word(ow.startMs(), ow.endMs(), ow.text(), chunk, ow.endsWithSpace()));
            }
            out.add(new LyricsLine(orig.startTimeMs(), orig.endTimeMs(), fullRoma, rWords,
                    orig.agentId(), orig.isDuet(), orig.isBG(), orig.songPart()));
        }
        return out;
    }

    private static List<LyricsLine> toLines(List<String> texts) {
        List<LyricsLine> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.equals("null")) {
                text = "";
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, stripParentheses(text).trim()));
        }
        return result;
    }

    public static boolean isPurelyLatinScript(@Nullable CharSequence text) {
        //noinspection SizeReplaceableByIsEmpty
        if (text == null || text.length() == 0) return true;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isDigit(cp)) continue;
            int type = Character.getType(cp);
            if (type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON
                    && script != Character.UnicodeScript.INHERITED) {
                return false;
            }
        }
        return true;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];
        for (int j = 0; j <= len2; j++) prev[j] = j;
        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            char c1 = s1.charAt(i - 1);
            for (int j = 1; j <= len2; j++) {
                char c2 = s2.charAt(j - 1);
                int cost = (c1 == c2) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, len2 + 1);
        }
        return prev[len2];
    }

    private static List<String> alignRomanizationAnchors(
            @Nullable List<Word> origWords,
            @Nullable String fullText) {
        if (origWords == null || origWords.isEmpty()) return Collections.emptyList();
        final int count = origWords.size();
        final boolean allowTransliteration = Settings.LYRICS_ALLOW_TRANSLITERATION.get();
        final boolean targetHasCjk = ScriptDetector.containsCjk(fullText);
        List<String> originalSyllables = new ArrayList<>(count);
        List<String> romanizedGuides = new ArrayList<>(count);
        for (Word w : origWords) {
            String text = w.text() != null ? w.text() : "";
            originalSyllables.add(text);
            String guide = wordReading(w, allowTransliteration);
            // A Han word reading cannot guide a Latin line reading. Fall back to the word's
            // own pronunciation, the same reading the line is likely built from.
            if (!targetHasCjk && ScriptDetector.containsCjk(guide)) {
                guide = wordReading(w, false);
            }
            if (guide.isEmpty() && isPurelyLatinScript(text)) {
                guide = text.trim();
            }
            romanizedGuides.add(guide);
        }
        return alignRomanizationAnchors(fullText != null ? fullText : "", romanizedGuides, originalSyllables);
    }

    /**
     * Partitions fullText into M segments that phonetically match the guides while preserving spaces.
     */
    private static List<String> alignRomanizationAnchors(
            String fullText,
            List<String> romanizedGuides,
            List<String> originalSyllables) {
        final String target = fullText;
        final int N = target.length();
        final int M = romanizedGuides.size();

        if (M == 0) return Collections.emptyList();
        if (N == 0) {
            List<String> empty = new ArrayList<>(M);
            for (int i = 0; i < M; i++) empty.add("");
            return empty;
        }

        boolean anyGuide = false;
        for (String g : romanizedGuides) {
            if (g != null && !g.trim().isEmpty()) {
                anyGuide = true;
                break;
            }
        }
        if (!anyGuide && !target.trim().isEmpty()) {
            String[] split = target.trim().split("\\s+");
            List<String> chunks = new ArrayList<>(M);
            if (split.length == M) {
                for (int i = 0; i < M; i++) {
                    chunks.add(split[i] + (i < M - 1 ? " " : ""));
                }
                return chunks;
            } else if (split.length > 0) {
                if (split.length < M) {
                    // When the source words are all Han/kana and the reading has fewer
                    // space-separated pieces than words, split the reading by characters.
                    // Otherwise a one-word reading such as "你的名字" would be assigned
                    // entirely to the last word and the other words would show the lyric.
                    if (ScriptDetector.containsCjk(target) || isAllCjk(originalSyllables)) {
                        return splitReadingByCharacter(target, M);
                    }
                    for (int i = 0; i < M; i++) {
                        int tokStart = (i * split.length) / M;
                        int tokEnd = ((i + 1) * split.length) / M;
                        String rom = (tokEnd > tokStart && tokStart < split.length) ? split[tokStart] : "";
                        chunks.add(rom + (!rom.isEmpty() && i < M - 1 ? " " : ""));
                    }
                } else {
                    for (int i = 0; i < M; i++) {
                        int tokStart = (i * split.length) / M;
                        int tokEnd = (i == M - 1) ? split.length : (((i + 1) * split.length) / M);
                        StringBuilder sb = new StringBuilder();
                        for (int k = tokStart; k < tokEnd && k < split.length; k++) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(split[k]);
                        }
                        if (i < M - 1) sb.append(" ");
                        chunks.add(sb.toString());
                    }
                }
                return chunks;
            }
        }

        double[][] dp = new double[M + 1][N + 1];
        int[][] path = new int[M + 1][N + 1];

        for (int i = 0; i <= M; i++) {
            Arrays.fill(dp[i], Double.MAX_VALUE);
            Arrays.fill(path[i], -1);
        }

        dp[0][0] = 0.0;

        for (int i = 1; i <= M; i++) {
            String guideRom = romanizedGuides.get(i - 1);
            boolean isLatin = (i - 1 < originalSyllables.size()) && isPurelyLatinScript(originalSyllables.get(i - 1));
            int guideLen = guideRom.length();

            // A Latin word reads as itself, so its length barely changes; other scripts may change a lot.
            int minLen = isLatin ? Math.max(1, guideLen - 1) : 1;
            int maxLen = isLatin ? guideLen + 3 : Math.max(guideLen * 3 + 3, 15);

            for (int j = 1; j <= N; j++) {
                double bestLocalCost = Double.MAX_VALUE;
                int bestPrevJ = -1;

                for (int len = minLen; len <= maxLen; len++) {
                    int k = j - len;
                    if (k < 0) break;

                    if (dp[i - 1][k] == Double.MAX_VALUE) continue;

                    String candidate = target.substring(k, j);
                    double segmentCost = calculateMatchCost(candidate, guideRom, isLatin);
                    double totalCost = dp[i - 1][k] + segmentCost;

                    if (totalCost < bestLocalCost) {
                        bestLocalCost = totalCost;
                        bestPrevJ = k;
                    }
                }

                dp[i][j] = bestLocalCost;
                path[i][j] = bestPrevJ;
            }
        }

        List<String> results = new ArrayList<>(Collections.nCopies(M, ""));
        int currJ = N;

        // If no partition covers the whole text, the cheapest one that gets furthest is used.
        if (dp[M][N] == Double.MAX_VALUE) {
            double minEndCost = Double.MAX_VALUE;
            for (int k = N; k >= 0; k--) {
                if (dp[M][k] < minEndCost) {
                    minEndCost = dp[M][k];
                    currJ = k;
                }
            }
        }

        for (int i = M; i > 0; i--) {
            int prevJ = path[i][currJ];
            if (prevJ == -1) {
                results.set(i - 1, "");
            } else {
                results.set(i - 1, target.substring(prevJ, currJ));
                currJ = prevJ;
            }
        }

        // Characters no partition covered go to the first or the last piece.
        if (currJ > 0 && !results.isEmpty()) {
            results.set(0, target.substring(0, currJ) + results.get(0));
        }
        int totalLen = 0;
        for (String r : results) totalLen += r.length();
        if (totalLen < N && !results.isEmpty()) {
            int safeStart = Math.min(target.length(), Math.max(0, totalLen));
            results.set(results.size() - 1, results.get(results.size() - 1) + target.substring(safeStart));
        }

        return results;
    }

    /** Whether every written word is in a script that does not put spaces between words. */
    private static boolean isAllCjk(List<String> syllables) {
        if (syllables.isEmpty()) {
            return false;
        }
        for (String syllable : syllables) {
            if (syllable == null || syllable.trim().isEmpty()
                    || !ScriptDetector.containsCjk(syllable)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a reading across words by code point. Used when a CJK reading has fewer spaces
     * than there are words, since a Han syllable or a romanized kana syllable is a character
     * rather than a whitespace-delimited token.
     */
    private static List<String> splitReadingByCharacter(String text, int count) {
        StringBuilder compact = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (!Character.isWhitespace(cp)) {
                compact.appendCodePoint(cp);
            }
        }
        text = compact.toString();

        List<String> chunks = new ArrayList<>(count);
        final int total = text.codePointCount(0, text.length());
        int at = 0;
        for (int i = 0; i < count; i++) {
            int endCodePoint = (int) ((long) total * (i + 1) / count);
            int end = text.offsetByCodePoints(0, endCodePoint);
            String chunk = text.substring(at, end);
            if (!chunk.isEmpty() && i < count - 1) {
                chunk += " ";
            }
            chunks.add(chunk);
            at = end;
        }
        return chunks;
    }

    private static double calculateMatchCost(String candidate, String guide, boolean isLatin) {
        String cTrim = candidate.trim();
        String gTrim = guide.trim();

        if (cTrim.isEmpty() && !gTrim.isEmpty()) return 50.0;

        String cLower = cTrim.toLowerCase(Locale.ROOT);
        String gLower = gTrim.toLowerCase(Locale.ROOT);
        int dist = levenshteinDistance(cLower, gLower);

        if (isLatin) {
            return dist * 50.0 + (Math.abs(candidate.length() - guide.length()) * 0.5);
        }

        int lenDiff = Math.abs(cTrim.length() - gTrim.length());
        boolean hasTrailingSpace = candidate.endsWith(" ");
        double bonus = hasTrailingSpace ? -0.5 : 0.0;

        return dist + (lenDiff * 0.8) + bonus;
    }

    public interface Callback {
        void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                         boolean perWord, @Nullable String localDictionary);

    }

    private record ProviderResult(List<LyricsLine> lines, boolean fromGoogle,
                                  boolean fromAI, @Nullable String model,
                                  @Nullable String localDictionary) {
    }

    /** The result of romanizing text online, and whether every non-Latin line was fetched. */
    private record TextRomanization(List<String> lines, boolean complete) {
    }
}

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
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.PinyinDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsRomanizer {

    public interface Callback {
        void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                         boolean perWord, @Nullable String localDictionary);

    }

    private static final String RULE_BASED_DICTIONARY = "Local (Rule-based)";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsRomanizer() {
    }

    @Nullable
    public static List<LyricsLine> getEmbeddedRomanization(Lyrics lyrics) {
        if (lyrics == null) return null;
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
        if (LyricsMerge.hasText(embedded)) {
            return embedded;
        }
        // Otherwise the line reading is built from the readings of its words.
        if (lyrics.lines() != null && LyricsMerge.anyWordHasRomaji(lyrics.lines())) {
            List<LyricsLine> synthesized = new ArrayList<>(lyrics.lines().size());
            for (LyricsLine l : lyrics.lines()) {
                if (l.hasWords()) {
                    StringBuilder sb = new StringBuilder();
                    for (Word w : l.words()) {
                        String r = w.romaji();
                        String part = (r != null && !r.trim().isEmpty() && !ScriptDetector.containsCjk(r) && !isDegradedRomajiToken(r))
                                ? r.trim()
                                : (ScriptDetector.containsKana(w.text()) ? KanaRomanizer.romanize(w.text()) : "");
                        part = stripParentheses(part).trim();
                        if (!part.isEmpty() && !ScriptDetector.containsCjk(part)) {
                            sb.append(part);
                            if (w.endsWithSpace() && !part.endsWith(" ")) {
                                sb.append(' ');
                            }
                        }
                    }
                    synthesized.add(new LyricsLine(l.startTimeMs(), sb.toString().trim()));
                } else {
                    synthesized.add(new LyricsLine(l.startTimeMs(), ""));
                }
            }
            if (LyricsMerge.hasText(synthesized)) {
                return synthesized;
            }
        }
        return null;
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

        // A reading the provider ships with wins, unless it is broken into fragments.
        List<LyricsLine> embedded = getEmbeddedRomanization(lyrics);
        final boolean perWord = LyricsMerge.anyWordHasRomaji(lyrics.lines());
        if ((LyricsMerge.hasText(embedded) || perWord)
                && !isProviderRomajiDegraded(embedded, lyrics.lines(), perWord)) {
            List<LyricsLine> result = alignIfWordSynced(lyrics, embedded, isWordSynced);
            boolean hasWordRomaji = perWord || (isWordSynced && result != null && LyricsMerge.anyWordHasRomaji(result));
            Utils.runOnMainThread(() -> callback.onRomanized(result, false, false, null, hasWordRomaji, null));
            return;
        }

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(stripParentheses(line.text()));
        }

        executor.execute(() -> {
            try {
                String provider = Settings.LYRICS_ROMANIZATION_PROVIDER.get();

                if (AiProviders.LOCAL.equals(provider)) {
                    if (!JapaneseDictStore.isReady()) JapaneseDictStore.ensure(null);
                    if (!PinyinDictStore.isReady()) PinyinDictStore.ensure(null);

                    LocalRomanizer.Result local = LocalRomanizer.romanize(lines);
                    List<LyricsLine> result = local != null ? toLines(local.lines) : null;
                    String dictionary = local != null ? local.dictionary : null;
                    if (!LyricsMerge.hasText(result)) {
                        result = ruleBased(lines);
                        dictionary = result != null ? RULE_BASED_DICTIONARY : null;
                    }
                    if (LyricsMerge.hasText(result)) {
                        List<LyricsLine> finalResult = alignIfWordSynced(lyrics, result, isWordSynced);
                        String finalDictionary = dictionary;
                        final boolean hasWordRomaji = isWordSynced && finalResult != null && LyricsMerge.anyWordHasRomaji(finalResult);
                        Utils.runOnMainThread(() -> callback.onRomanized(
                                finalResult, false, false, null, hasWordRomaji, finalDictionary));
                        return;
                    }
                    // Local romanization yielded no text; fall through to Google/online fallback.
                }

                if (AiProviders.isAi(provider)) {
                    List<LyricsLine> aiCached = LyricsCache.getRomanizationAI(track, source, lines);
                    if (aiCached != null) {
                        List<LyricsLine> aligned = alignIfWordSynced(lyrics, aiCached, isWordSynced);
                        final boolean hasWordRomaji = isWordSynced && aligned != null && LyricsMerge.anyWordHasRomaji(aligned);
                        Utils.runOnMainThread(() -> callback.onRomanized(aligned, false, true, provider, hasWordRomaji, null));
                        return;
                    }

                    String prompt = OpenAIClient.renderPrompt(Settings.LYRICS_AI_ROMANIZATION_PROMPT.get(),
                            "romanization", LyricsRequests.romanizationLanguage(),
                            track.title(), track.artist(), lines);
                    AiProviders.Result ai = AiProviders.mapLines(provider, prompt, lines);
                    if (ai != null && ai.lines() != null && !ai.lines().isEmpty()) {
                        List<LyricsLine> aiLines = alignIfWordSynced(lyrics, toLines(ai.lines()), isWordSynced);
                        if (LyricsMerge.hasText(aiLines)) {
                            LyricsCache.putRomanizationAI(track, source, lines, aiLines);
                            final boolean hasWordRomaji = isWordSynced && LyricsMerge.anyWordHasRomaji(aiLines);
                            Utils.runOnMainThread(() -> callback.onRomanized(aiLines, false, true, ai.model(), hasWordRomaji, null));
                            return;
                        }
                    }
                }

                List<LyricsLine> romanized;
                boolean online;
                if (isWordSynced) {
                    romanized = romanizeWordSynced(lyrics);
                    online = LyricsMerge.hasText(romanized);
                } else {
                    romanized = LyricsCache.getRomanization(track, source, lines);
                    online = romanized != null;
                    if (romanized == null) {
                        romanized = romanizeLineSynced(lyrics);
                        if (LyricsMerge.hasText(romanized)) {
                            LyricsCache.putRomanization(track, source, lines, romanized);
                            online = true;
                        }
                    }
                }

                // The last resort. Its reading is not stored, so an online one is used again
                // as soon as there is one.
                LocalRomanizer.Result local = !LyricsMerge.hasText(romanized)
                        && (Settings.LYRICS_LOCAL_ROMANIZATION.get() || AiProviders.LOCAL.equals(provider) || AiProviders.isAi(provider))
                        ? LocalRomanizer.romanize(lines) : null;

                List<LyricsLine> result = romanized;
                if (local != null) {
                    result = alignIfWordSynced(lyrics, toLines(local.lines), isWordSynced);
                }
                if (!LyricsMerge.hasText(result)) {
                    List<LyricsLine> ruleBased = ruleBased(lines);
                    if (ruleBased != null) {
                        result = alignIfWordSynced(lyrics, ruleBased, isWordSynced);
                    }
                }

                List<LyricsLine> finalResult = result;
                final boolean fromGoogle = LyricsMerge.hasText(result) && online && local == null;
                final boolean hasWordRomaji = isWordSynced && finalResult != null && LyricsMerge.anyWordHasRomaji(finalResult);
                Utils.runOnMainThread(() -> callback.onRomanized(
                        finalResult, fromGoogle, false, null, hasWordRomaji,
                        local != null ? local.dictionary : null));
            } catch (Exception ex) {
                Logger.printException(() -> "romanize failure", ex);
                Utils.runOnMainThread(
                        () -> callback.onRomanized(null, false, false, null, false, null));
            }
        });
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

    private static List<LyricsLine> romanizeWordSynced(Lyrics lyrics) {
        if (lyrics.lines() == null) return Collections.emptyList();

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

        List<String> fullLineResults = romanizeTexts(allLines);
        List<String> isolatedResults = !allSyllables.isEmpty() ? romanizeTexts(allSyllables) : Collections.emptyList();

        List<LyricsLine> resultLines = new ArrayList<>(origLines.size());
        int globalSyllableIndex = 0;

        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine line = origLines.get(i);
            String fullLineRom = (i < fullLineResults.size() && fullLineResults.get(i) != null) ? fullLineResults.get(i) : "";
            int count = lineStructure[i];
            List<Word> origWords = line.words();

            if (count == 0 || !line.hasWords()) {
                resultLines.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(),
                        fullLineRom, Collections.emptyList(), line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
                continue;
            }

            int endSyllableIndex = Math.min(globalSyllableIndex + count, isolatedResults.size());
            List<String> romanizedGuides = (globalSyllableIndex < isolatedResults.size())
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

    private static List<LyricsLine> romanizeLineSynced(Lyrics lyrics) {
        if (lyrics.lines() == null) return Collections.emptyList();

        List<LyricsLine> origLines = lyrics.lines();
        List<String> texts = new ArrayList<>(origLines.size());
        for (LyricsLine l : origLines) {
            String text = l.text();
            texts.add(text != null ? stripParentheses(text).trim() : "");
        }

        List<String> romanizedResults = romanizeTexts(texts);

        List<LyricsLine> result = new ArrayList<>(origLines.size());
        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine origLine = origLines.get(i);
            String origText = texts.get(i);
            String roma = (i < romanizedResults.size() && romanizedResults.get(i) != null)
                    ? romanizedResults.get(i) : origText;
            if (roma.isEmpty()) {
                roma = origText;
            }

            result.add(new LyricsLine(origLine.startTimeMs(), origLine.endTimeMs(),
                    roma, Collections.emptyList(), origLine.agentId(), origLine.isDuet(), origLine.isBG(), origLine.songPart()));
        }
        return result;
    }

    /**
     * Romanizes the texts online. A text already in Latin script, or one that could not be
     * read, is returned as it is.
     */
    private static List<String> romanizeTexts(List<String> texts) {
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
            return result;
        }

        String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        List<String> fetched = LyricsMerge.mapLinesOnline(toFetch, batch -> {
            try {
                return TextTranslator.romanize(batch, language);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        if (fetched != null) {
            for (int k = 0; k < indices.size(); k++) {
                result.set(indices.get(k), fetched.get(k));
            }
        }
        return result;
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
        if (s.equals("ceng") || s.equals("cuan") || s.equals("zuan") || s.equals("zhe")
                || s.equals("jin") || s.equals("dong") || s.equals("sheng") || s.equals("chuang")
                || s.equals("xian") || s.equals("qian") || s.equals("tian") || s.equals("lian")
                || s.equals("jian") || s.equals("bian") || s.equals("pian") || s.equals("mian")
                || s.equals("dian") || s.equals("nian") || s.equals("huan") || s.equals("guan")
                || s.equals("kuan") || s.equals("duan") || s.equals("tuan") || s.equals("luan")
                || s.equals("ruan") || s.equals("xuan") || s.equals("quan") || s.equals("juan")
                || s.equals("yang") || s.equals("ying") || s.equals("yong") || s.equals("wang")
                || s.equals("weng") || s.equals("gu") || s.equals("xi")) {
            return true;
        }
        return false;
    }

    private static boolean isDegradedRomajiToken(@Nullable String t) {
        if (t == null || t.isEmpty()) return false;
        String s = t.trim().toLowerCase(Locale.ROOT);
        if (s.contains(" ")) {
            for (String part : s.split("\\s+")) {
                if (isDegradedRomajiToken(part)) return true;
            }
            return false;
        }
        // A counter reading left with its digit, such as 1-ri, 1-tsu or 2nin.
        if (s.matches("^\\d+-[a-z]+$") || s.matches("^\\d+[a-z]+$")) {
            return true;
        }
        // A sokuon written as a symbol, such as ~tsu or -tsu.
        if (s.contains("~tsu") || s.contains("-tsu") || s.contains("～tsu")
                || s.endsWith("~") || s.startsWith("~") || s.contains("～") || s.contains("~")) {
            return true;
        }
        // A symbol or punctuation mark on its own.
        if (s.equals("-") || s.equals("~") || s.equals("～") || s.equals("^") || s.equals("_")) {
            return true;
        }
        // A pinyin syllable, which has no place in a Japanese reading.
        if (isMandarinPinyinToken(s)) {
            return true;
        }
        // A doubled consonant on its own, which is a sokuon split from its mora.
        if (s.equals("pp") || s.equals("tt") || s.equals("kk") || s.equals("ss")
                || s.equals("cc") || s.equals("dd") || s.equals("bb") || s.equals("ff")
                || s.equals("gg") || s.equals("hh") || s.equals("jj") || s.equals("mm")
                || s.equals("rr") || s.equals("ww") || s.equals("yy") || s.equals("zz")) {
            return true;
        }
        return false;
    }

    public static boolean isProviderRomajiDegraded(@Nullable List<LyricsLine> embedded,
                                                   @Nullable List<LyricsLine> originalLines,
                                                   boolean perWord) {
        if (!perWord && (embedded == null || embedded.isEmpty())) {
            return true;
        }

        int totalTokens = 0;
        int shortTokens = 0;
        int totalChars = 0;
        int cjkCount = 0;
        int totalChecked = 0;
        int allSingleLetterTokens = 0;

        if (embedded != null && !embedded.isEmpty()) {
            for (int li = 0; li < embedded.size(); li++) {
                LyricsLine line = embedded.get(li);
                String text = line.text();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                totalChecked++;
                if (ScriptDetector.containsCjk(text)) {
                    cjkCount++;
                }

                LyricsLine origLine = (originalLines != null && li < originalLines.size()) ? originalLines.get(li) : null;
                String origText = origLine != null ? origLine.text() : null;

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
                    if (!t.isEmpty()) {
                        if (isDegradedRomajiToken(t)) {
                            return true; // trigger for pp 1-ri etc
                        }
                        totalTokens++;
                        totalChars += t.length();
                        if (t.length() <= 2) {
                            shortTokens++;
                        }
                        if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                            singleLetterTokens++;
                            allSingleLetterTokens++;
                        }
                    }
                }
                // Several lone letters mean the morae were split apart.
                if (singleLetterTokens >= 3 && origText != null && ScriptDetector.containsKana(origText)) {
                    return true;
                }
            }
            if (totalChecked == 0) return true;
            if ((double) cjkCount / totalChecked > 0.20) {
                return true;
            }
            if (originalLines != null) {
                int origNonEmpty = 0;
                for (LyricsLine l : originalLines) {
                    if (l.text() != null && !l.text().trim().isEmpty()) {
                        origNonEmpty++;
                    }
                }
                if (origNonEmpty > 0 && (double) totalChecked / origNonEmpty < 0.40) {
                    return true;
                }
            }
        }

        // The per word readings are checked the same way.
        if (perWord && originalLines != null) {
            int totalWordTokens = 0;
            int shortWordTokens = 0;
            int charWordTokens = 0;
            int allSingleWordTokens = 0;
            for (LyricsLine l : originalLines) {
                if (!l.hasWords()) continue;
                String origLineText = l.text();
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
                            if (!t.isEmpty()) {
                                if (isDegradedRomajiToken(t)) {
                                    return true; // trigger for 1-ri pp etc
                                }
                                totalWordTokens++;
                                charWordTokens += t.length();
                                if (t.length() <= 2) {
                                    shortWordTokens++;
                                }
                                if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                                    singleWordTokens++;
                                    allSingleWordTokens++;
                                }
                            }
                        }
                    }
                }
                if (singleWordTokens >= 3 && origLineText != null && ScriptDetector.containsKana(origLineText)) {
                    return true;
                }
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
            if (avgLen < 1.4 || singleLetterRatio > 0.5) {
                return true;
            }
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
        List<String> originalSyllables = new ArrayList<>(count);
        List<String> romanizedGuides = new ArrayList<>(count);
        for (Word w : origWords) {
            String text = w.text() != null ? w.text() : "";
            originalSyllables.add(text);
            String guide = w.romaji();
            if (guide == null || guide.trim().isEmpty() || ScriptDetector.containsCjk(guide)) {
                if (isPurelyLatinScript(text)) {
                    guide = text.trim();
                } else if (ScriptDetector.containsKana(text)) {
                    guide = KanaRomanizer.romanize(text).trim();
                } else if (ScriptDetector.contains(text, Character.UnicodeScript.HANGUL)) {
                    guide = HangulRomanizer.romanize(text).trim();
                } else {
                    guide = "";
                }
            } else {
                guide = guide.trim();
            }
            romanizedGuides.add(guide != null ? guide : "");
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
        String target = fullText;
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
}

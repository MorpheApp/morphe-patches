/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;

import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.PinyinDictStore;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsRomanizer {

    public interface Callback {
        void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                         boolean perWord, @Nullable String localDictionary);

        default void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                         boolean perWord) {
            onRomanized(romanizedLines, fromGoogle, fromAI, aiModel, perWord, null);
        }
    }

    private static final String SYSTEM_PROMPT =
            "You are a lyrics romanizer. Output only the romanized text without explanations.";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final Map<String, String> KANA_DIGRAPHS = createKanaDigraphs();
    private static final Map<String, String> KANA_SINGLES = createKanaSingles();

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
        // fallback make line romaji from each word romaji
        if (lyrics.lines() != null && LyricsMerge.anyWordHasRomaji(lyrics.lines())) {
            List<LyricsLine> synthesized = new ArrayList<>(lyrics.lines().size());
            for (LyricsLine l : lyrics.lines()) {
                if (l.hasWords()) {
                    StringBuilder sb = new StringBuilder();
                    for (Word w : l.words()) {
                        String r = w.romaji();
                        String part = (r != null && !r.trim().isEmpty() && !hasCjk(r) && !isDegradedRomajiToken(r))
                                ? r.trim()
                                : (containsJapanese(w.text()) ? romanizeJapanese(w.text()) : "");
                        part = cleanJapaneseQuotes(stripParentheses(part)).trim();
                        if (!part.isEmpty() && !hasCjk(part)) {
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

    private static volatile boolean currentSongIsJapanese = false;

    public static boolean isSongJapanese() {
        return currentSongIsJapanese;
    }

    public static void setCurrentSongIsJapanese(boolean isJapanese) {
        currentSongIsJapanese = isJapanese;
    }

    public static boolean containsKanji(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); i++) {
            if (isKanji(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsHangul(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private static final String[] HANGUL_CHOSEONG = {
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s",
        "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    };

    private static final String[] HANGUL_JUNGSEONG = {
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o",
        "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu",
        "eu", "ui", "i"
    };

    private static final String[] HANGUL_JONGSEONG = {
        "", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk",
        "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps",
        "t", "t", "ng", "t", "t", "k", "t", "p", "t"
    };

    private static final String[] HANGUL_JONGSEONG_LIAISON = {
        "", "g", "kk", "ks", "n", "nj", "nh", "d", "r", "lg",
        "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "ps",
        "s", "ss", "ng", "j", "ch", "k", "t", "p", "h"
    };

    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_END = 0xD7A3;

    public static String romanizeHangulChar(char c, @Nullable Character nextC) {
        int code = (int) c;
        if (code < HANGUL_BASE || code > HANGUL_END) {
            return String.valueOf(c);
        }
        int offset = code - HANGUL_BASE;
        int choseong = offset / 588;
        int jungseong = (offset % 588) / 28;
        int jongseong = offset % 28;

        String initial = HANGUL_CHOSEONG[choseong];
        String vowel = HANGUL_JUNGSEONG[jungseong];
        String fin = "";
        if (jongseong > 0) {
            if (nextC != null) {
                int nextCode = (int) nextC;
                if (nextCode >= HANGUL_BASE && nextCode <= HANGUL_END) {
                    int nextOffset = nextCode - HANGUL_BASE;
                    int nextChoseong = nextOffset / 588;
                    if (nextChoseong == 11) {
                        String l = HANGUL_JONGSEONG_LIAISON[jongseong];
                        fin = (l != null && !l.isEmpty()) ? l : HANGUL_JONGSEONG[jongseong];
                    } else {
                        fin = HANGUL_JONGSEONG[jongseong];
                    }
                } else {
                    fin = HANGUL_JONGSEONG[jongseong];
                }
            } else {
                fin = HANGUL_JONGSEONG[jongseong];
            }
        }
        return initial + vowel + fin;
    }

    public static String romanizeHangul(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= HANGUL_BASE && c <= HANGUL_END) {
                Character nextC = (i + 1 < text.length()) ? text.charAt(i + 1) : null;
                sb.append(romanizeHangulChar(c, nextC));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean containsCyrillic(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.CYRILLIC) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsArabic(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsHanzi(@Nullable CharSequence text) {
        return containsKanji(text);
    }

    public static boolean containsGreek(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.GREEK) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsHebrew(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HEBREW) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsDevanagari(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.DEVANAGARI) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsThai(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.THAI) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public static String detectLanguageContext(@Nullable Lyrics lyrics, @Nullable TrackInfo track) {
        StringBuilder allText = new StringBuilder();
        if (track != null) {
            if (track.title() != null) allText.append(track.title()).append(' ');
            if (track.artist() != null) allText.append(track.artist()).append(' ');
        }
        if (lyrics != null && lyrics.lines() != null) {
            for (LyricsLine line : lyrics.lines()) {
                if (line.text() != null) allText.append(line.text()).append(' ');
            }
        }

        CharSequence seq = allText;
        int hangulCount = 0;
        int kanaCount = 0;
        int cyrillicCount = 0;
        int hanziCount = 0;
        int arabicCount = 0;
        int hebrewCount = 0;
        int devanagariCount = 0;
        int thaiCount = 0;
        int greekCount = 0;

        for (int i = 0; i < seq.length(); ) {
            int cp = Character.codePointAt(seq, i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HANGUL) {
                hangulCount++;
            } else if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                kanaCount++;
            } else if (script == Character.UnicodeScript.CYRILLIC) {
                cyrillicCount++;
            } else if (script == Character.UnicodeScript.HAN) {
                hanziCount++;
            } else if (script == Character.UnicodeScript.ARABIC) {
                arabicCount++;
            } else if (script == Character.UnicodeScript.HEBREW) {
                hebrewCount++;
            } else if (script == Character.UnicodeScript.DEVANAGARI) {
                devanagariCount++;
            } else if (script == Character.UnicodeScript.THAI) {
                thaiCount++;
            } else if (script == Character.UnicodeScript.GREEK) {
                greekCount++;
            }
        }

        if (hebrewCount > 0 && hebrewCount >= arabicCount && hebrewCount >= kanaCount && hebrewCount >= hangulCount && hebrewCount >= hanziCount && hebrewCount >= cyrillicCount) {
            return "hebrew";
        }
        if (devanagariCount > 0 && devanagariCount >= arabicCount && devanagariCount >= kanaCount && devanagariCount >= hangulCount && devanagariCount >= hanziCount && devanagariCount >= cyrillicCount) {
            return "devanagari";
        }
        if (thaiCount > 0 && thaiCount >= arabicCount && thaiCount >= kanaCount && thaiCount >= hangulCount && thaiCount >= hanziCount && thaiCount >= cyrillicCount) {
            return "thai";
        }
        if (greekCount > 0 && greekCount >= arabicCount && greekCount >= kanaCount && greekCount >= hangulCount && greekCount >= hanziCount && greekCount >= cyrillicCount) {
            return "greek";
        }
        if (arabicCount > 0 && arabicCount >= kanaCount && arabicCount >= hangulCount && arabicCount >= hanziCount && arabicCount >= cyrillicCount) {
            return "arabic";
        }
        if (hangulCount > 0 && hangulCount >= kanaCount) {
            return "korean";
        }
        if (kanaCount > 0) {
            return "japanese";
        }
        if (hanziCount > 0 && kanaCount == 0) {
            return "chinese";
        }
        if (cyrillicCount > 0) {
            return "cyrillic";
        }
        return "unknown";
    }

    public static void romanize(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        romanize(track, lyrics, source, false, callback);
    }

    public static void romanize(TrackInfo track, Lyrics lyrics, String source,
                                boolean forceFullTransliteration, Callback callback) {
        Utils.verifyOnMainThread();

        final String langContext = detectLanguageContext(lyrics, track);
        currentSongIsJapanese = "japanese".equals(langContext);

        boolean anyLineHasWords = false;
        if (lyrics != null && lyrics.lines() != null) {
            for (LyricsLine l : lyrics.lines()) {
                if (l.hasWords()) {
                    anyLineHasWords = true;
                    break;
                }
            }
        }
        final boolean isWordSynced = anyLineHasWords;

        List<LyricsLine> embedded = forceFullTransliteration ? null : getEmbeddedRomanization(lyrics);
        final boolean perWord = !forceFullTransliteration && LyricsMerge.anyWordHasRomaji(lyrics != null ? lyrics.lines() : null);
        final boolean degraded = !forceFullTransliteration && isProviderRomajiDegraded(embedded, lyrics != null ? lyrics.lines() : null, perWord);

        // prioritize provider romaji first if it look good and not broken
        if (!forceFullTransliteration && !degraded && (LyricsMerge.hasText(embedded) || perWord)) {
            List<LyricsLine> result = (isWordSynced && embedded != null)
                    ? alignLinesToWordSync(lyrics, embedded)
                    : embedded;
            Utils.runOnMainThread(() -> callback.onRomanized(result, false, false, null, perWord || isWordSynced, null));
            return;
        }

        List<String> lines = new ArrayList<>(lyrics != null && lyrics.lines() != null ? lyrics.lines().size() : 0);
        if (lyrics != null && lyrics.lines() != null) {
            for (LyricsLine line : lyrics.lines()) {
                String text = line.text();
                lines.add(text != null ? cleanJapaneseQuotes(stripParentheses(text)).trim() : "");
            }
        }

        executor.execute(() -> {
            try {
                String provider = Settings.LYRICS_ROMANIZATION_PROVIDER.get().toLowerCase(Locale.ROOT);
                boolean isLocal = "local".equals(provider) || "offline".equals(provider);

                if (isLocal) {
                    if (!JapaneseDictStore.isReady()) JapaneseDictStore.ensure(null);
                    if (!PinyinDictStore.isReady()) PinyinDictStore.ensure(null);

                    LocalRomanizer.Result local = LocalRomanizer.romanize(lines);
                    List<LyricsLine> result = local != null ? toLines(local.lines) : null;
                    if (isWordSynced && result != null) {
                        result = alignLinesToWordSync(lyrics, result);
                    }

                    // If local dictionary returned nothing or not available, fall back to rule-based romanization
                    if (result == null || !LyricsMerge.hasText(result)) {
                        List<String> ruleBased = new ArrayList<>(lines.size());
                        boolean ruleApplied = false;
                        for (String line : lines) {
                            String rom = line;
                            if (containsJapanese(line)) {
                                rom = romanizeJapanese(line);
                            } else if (containsHangul(line)) {
                                rom = romanizeHangul(line);
                            }
                            if (!rom.equals(line)) {
                                ruleApplied = true;
                            }
                            ruleBased.add(rom);
                        }
                        if (ruleApplied) {
                            result = toLines(ruleBased);
                            if (isWordSynced) {
                                result = alignLinesToWordSync(lyrics, result);
                            }
                        }
                    }

                    boolean hasResult = result != null && LyricsMerge.hasText(result);
                    final List<LyricsLine> finalResult = hasResult ? result : null;
                    final String dictUsed = (hasResult && local != null && local.dictionary != null)
                            ? local.dictionary
                            : (hasResult ? "Local (Rule-based)" : null);
                    Utils.runOnMainThread(() -> callback.onRomanized(
                            finalResult, false, false, null, isWordSynced, dictUsed));
                    return;
                }

                boolean isAi = "gemini".equals(provider) || "openrouter".equals(provider)
                        || "openai".equals(provider) || "openai_compatible".equals(provider);
                boolean useAi = isAi || Settings.LYRICS_USE_AI_ROMANIZATION.get();

                if (useAi) {
                    final String effectiveProvider = isAi ? provider : "openai_compatible";
                    final String targetLanguage = LyricsRequests.romanizationLanguage();

                    List<LyricsLine> aiCached = LyricsCache.getRomanizationAI(track, source, lines);
                    if (aiCached != null) {
                        final List<LyricsLine> alignedCached = isWordSynced ? alignLinesToWordSync(lyrics, aiCached) : aiCached;
                        Utils.runOnMainThread(() -> callback.onRomanized(alignedCached, false, true, effectiveProvider, isWordSynced, null));
                        return;
                    }

                    List<String> aiResult = null;
                    String modelUsed = null;

                    switch (effectiveProvider) {
                        case "gemini": {
                            String apiKey = Settings.LYRICS_GEMINI_API_KEY.get();
                            String model = Settings.LYRICS_GEMINI_MODEL.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = GeminiClient.romanize(lines, targetLanguage, track.title(), track.artist(), apiKey, model);
                                modelUsed = model;
                            }
                            break;
                        }
                        case "openrouter": {
                            String apiKey = Settings.LYRICS_OPENROUTER_API_KEY.get();
                            String model = Settings.LYRICS_OPENROUTER_MODEL.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = aiRomanize(lines, targetLanguage, track.title(), track.artist(),
                                        "https://openrouter.ai/api/v1/chat/completions", apiKey, model);
                                modelUsed = model;
                            }
                            break;
                        }
                        case "openai": {
                            String apiKey = Settings.LYRICS_OPENAI_API_KEY.get();
                            String model = Settings.LYRICS_OPENAI_MODEL.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = aiRomanize(lines, targetLanguage, track.title(), track.artist(),
                                        "https://api.openai.com/v1/chat/completions", apiKey, model);
                                modelUsed = model;
                            }
                            break;
                        }
                        case "openai_compatible":
                        default: {
                            String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                            String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                            String model = Settings.LYRICS_AI_MODEL.get();
                            aiResult = aiRomanize(lines, targetLanguage, track.title(), track.artist(), baseUrl, apiToken, model);
                            modelUsed = model;
                            break;
                        }
                    }

                    if (aiResult != null && !aiResult.isEmpty()) {
                        List<LyricsLine> rawAiLines = toLines(aiResult);
                        List<LyricsLine> aiLines = isWordSynced ? alignLinesToWordSync(lyrics, rawAiLines) : rawAiLines;
                        LyricsCache.putRomanizationAI(track, source, lines, aiLines);
                        final String finalModel = modelUsed;
                        Utils.runOnMainThread(() -> callback.onRomanized(aiLines, false, true, finalModel, isWordSynced, null));
                        return;
                    }
                }

                List<LyricsLine> romanized = null;
                boolean online = false;
                if (isWordSynced) {
                    romanized = romanizeWordSynced(lyrics);
                    online = romanized != null && LyricsMerge.hasText(romanized);
                } else {
                    romanized = LyricsCache.getRomanization(track, source, lines);
                    online = romanized != null;
                    if (romanized == null) {
                        romanized = romanizeLineSynced(lyrics);
                        if (romanized != null && LyricsMerge.hasText(romanized)) {
                            LyricsCache.putRomanization(track, source, lines, romanized);
                            online = true;
                        }
                    }
                }

                // The last resort. Its reading is not stored, so an online one is used again
                // as soon as there is one.
                LocalRomanizer.Result local = (romanized == null || !LyricsMerge.hasText(romanized))
                        && Settings.LYRICS_LOCAL_ROMANIZATION.get()
                        ? LocalRomanizer.romanize(lines) : null;

                List<LyricsLine> result = local != null ? toLines(local.lines) : romanized;
                if (isWordSynced && local != null && result != null) {
                    result = alignLinesToWordSync(lyrics, result);
                }

                // If local dictionary was not enabled or returned nothing, fall back to rule-based romanization
                if (result == null || !LyricsMerge.hasText(result)) {
                    List<String> ruleBased = new ArrayList<>(lines.size());
                    boolean ruleApplied = false;
                    for (String line : lines) {
                        String rom = line;
                        if (containsJapanese(line)) {
                            rom = romanizeJapanese(line);
                        } else if (containsHangul(line)) {
                            rom = romanizeHangul(line);
                        }
                        if (!rom.equals(line)) {
                            ruleApplied = true;
                        }
                        ruleBased.add(rom);
                    }
                    if (ruleApplied) {
                        result = toLines(ruleBased);
                        if (isWordSynced) {
                            result = alignLinesToWordSync(lyrics, result);
                        }
                    }
                }

                boolean hasResult = result != null && LyricsMerge.hasText(result);
                final List<LyricsLine> finalResult = result;
                final boolean fromGoogle = hasResult && online && local == null;
                Utils.runOnMainThread(() -> callback.onRomanized(
                        finalResult, fromGoogle, false, null, isWordSynced,
                        local != null ? local.dictionary : null));
            } catch (Throwable ignored) {
                Utils.runOnMainThread(
                        () -> callback.onRomanized(null, false, false, null, false, null));
            }
        });
    }

    @NonNull
    public static List<LyricsLine> romanizeWordSynced(@NonNull Lyrics lyrics) {
        if (lyrics.lines() == null) return Collections.emptyList();

        List<LyricsLine> origLines = lyrics.lines();
        List<String> allLines = new ArrayList<>(origLines.size());
        List<String> allSyllables = new ArrayList<>();
        int[] lineStructure = new int[origLines.size()];

        for (int i = 0; i < origLines.size(); i++) {
            LyricsLine line = origLines.get(i);
            String text = line.text() != null ? cleanJapaneseQuotes(stripParentheses(line.text())).trim() : "";
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

    @NonNull
    public static List<LyricsLine> romanizeWordSynced(@NonNull Lyrics lyrics, @NonNull String langContext) {
        return romanizeWordSynced(lyrics);
    }

    @NonNull
    public static List<LyricsLine> romanizeLineSynced(@NonNull Lyrics lyrics) {
        if (lyrics.lines() == null) return Collections.emptyList();

        List<LyricsLine> origLines = lyrics.lines();
        List<String> texts = new ArrayList<>(origLines.size());
        for (LyricsLine l : origLines) {
            String text = l.text();
            texts.add(text != null ? cleanJapaneseQuotes(stripParentheses(text)).trim() : "");
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

    @NonNull
    public static List<LyricsLine> romanizeLineSynced(@NonNull Lyrics lyrics, @NonNull String langContext) {
        return romanizeLineSynced(lyrics);
    }

    private static final int GOOGLE_MAX_RETRIES = 3;
    private static final int GOOGLE_RETRY_DELAY_MS = 500;

    @NonNull
    public static List<String> romanizeTexts(@NonNull List<String> texts) {
        List<Integer> validIndices = new ArrayList<>();
        List<String> textsToFetch = new ArrayList<>();

        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            if (text != null && !isPurelyLatinScript(text)) {
                validIndices.add(index);
                textsToFetch.add(text);
            }
        }

        if (textsToFetch.isEmpty()) {
            return texts;
        }

        final int BATCH_SIZE = 50;
        Map<Integer, String> resultsMap = new HashMap<>();

        for (int i = 0; i < textsToFetch.size(); i += BATCH_SIZE) {
            int toIndex = Math.min(i + BATCH_SIZE, textsToFetch.size());
            List<String> batch = textsToFetch.subList(i, toIndex);
            StringBuilder batchTextBuilder = new StringBuilder();
            for (int b = 0; b < batch.size(); b++) {
                if (b > 0) batchTextBuilder.append('|');
                batchTextBuilder.append(batch.get(b));
            }
            String batchText = batchTextBuilder.toString();

            try {
                String[] batchResultArray = fetchRomanizationWithRetry(batchText, 0);

                for (int batchIndex = 0; batchIndex < batch.size(); batchIndex++) {
                    String originalText = batch.get(batchIndex);
                    int targetIndex = validIndices.get(i + batchIndex);
                    if (batchResultArray != null && batchIndex < batchResultArray.length) {
                        resultsMap.put(targetIndex, batchResultArray[batchIndex]);
                    } else {
                        resultsMap.put(targetIndex, originalText);
                    }
                }
            } catch (Throwable e) {
                Logger.printException(() -> "LyricsRomanizer: Batch failed", e);
                for (int batchIndex = 0; batchIndex < batch.size(); batchIndex++) {
                    int targetIndex = validIndices.get(i + batchIndex);
                    resultsMap.put(targetIndex, batch.get(batchIndex));
                }
            }
        }

        List<String> result = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String mapped = resultsMap.get(index);
            result.add(mapped != null ? mapped : (text != null ? text : ""));
        }
        return result;
    }

    private static String[] fetchRomanizationWithRetry(String text, int attempt) throws Exception {
        try {
            String encoded = URLEncoder.encode(text, "UTF-8");
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=rm&q=" + encoded;

            HttpURLConnection conn;
            if (encoded.length() > 2000) {
                conn = Requester.openConnection("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=rm");
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setDoOutput(true);
                byte[] payload = ("q=" + encoded).getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream stream = conn.getOutputStream()) {
                    stream.write(payload);
                }
            } else {
                conn = Requester.openConnection(url);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            }

            int status = conn.getResponseCode();
            if (status == 429) {
                throw new IOException("Rate Limit Exceeded");
            }
            if (status != 200) {
                throw new IOException("Google Translate error: HTTP " + status);
            }

            String jsonStr = Requester.parseStringAndDisconnect(conn);
            JSONArray data = new JSONArray(jsonStr);

            JSONArray segmentArray = data.optJSONArray(0);
            StringBuilder fullRomanizedBuilder = new StringBuilder();
            if (segmentArray != null) {
                for (int s = 0; s < segmentArray.length(); s++) {
                    JSONArray segment = segmentArray.optJSONArray(s);
                    if (segment == null) continue;
                    String part = segment.optString(3);
                    if (part.isEmpty() || "null".equals(part)) {
                        part = segment.optString(2);
                    }
                    if (part.isEmpty() || "null".equals(part)) {
                        part = segment.optString(0);
                    }
                    if (!"null".equals(part)) {
                        fullRomanizedBuilder.append(part);
                    }
                }
            }

            String fullRomanizedString = fullRomanizedBuilder.toString();
            if (fullRomanizedString.isEmpty()) {
                return text.split("\\|", -1);
            }

            return fullRomanizedString
                    .replaceAll("\\s*\\|\\s*", "|")
                    .split("\\|", -1);

        } catch (Exception error) {
            if (attempt < GOOGLE_MAX_RETRIES) {
                long delay = (long) (GOOGLE_RETRY_DELAY_MS * Math.pow(2, attempt));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {}
                return fetchRomanizationWithRetry(text, attempt + 1);
            }
            throw error;
        }
    }

    @Nullable
    private static List<String> aiRomanize(List<String> lines, String targetLanguage,
            String title, String artist, String baseUrl, String apiToken, String model) {
        String prompt = OpenAIClient.renderPrompt(Settings.LYRICS_AI_ROMANIZATION_PROMPT.get(),
                "romanization", targetLanguage, title, artist, lines);
        return OpenAIClient.mapLines(baseUrl, apiToken, model, prompt, null, lines);
    }

    public static String buildRomanizePrompt(List<String> lines, String targetLang,
            String title, String artist) {
        StringBuilder sb = new StringBuilder(200 + lines.size() * 50);
        sb.append("Convert each line below to Latin script (Romaji for Japanese, Pinyin for Chinese, Revised Romanization for Korean, Latin transliteration for Cyrillic/Russian).\n");
        sb.append("Song: ").append(title).append(" by ").append(artist).append("\n\n");
        sb.append("Rules:\n");
        sb.append("- Output exactly ").append(lines.size()).append(" numbered lines (format: 1. <romanized text>)\n");
        sb.append("- Do not include original lyrics\n");
        sb.append("- Use standard modified Hepburn for Japanese\n");
        sb.append("- If already in Latin script, output SKIP\n\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static List<LyricsLine> collectMatchingRomanizations(
            Map<String, List<LyricsLine>> romanizations, List<LyricsLine> allLines) {
        return collectMatchingRomanizations(romanizations, allLines, true);
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
                text = cleanJapaneseQuotes(stripParentheses(text)).trim();
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

    public static String cleanJapaneseQuotes(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text;
    }

    public static String stripParentheses(@Nullable String text) {
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

    public static boolean isMandarinPinyinToken(@Nullable String t) {
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

    public static boolean isDegradedRomajiToken(@Nullable String t) {
        if (t == null || t.isEmpty()) return false;
        String s = t.trim().toLowerCase(Locale.ROOT);
        if (s.contains(" ")) {
            for (String part : s.split("\\s+")) {
                if (isDegradedRomajiToken(part)) return true;
            }
            return false;
        }
        // weird number words artifact like 1-ri 1-tsu 2nin
        if (s.matches("^\\d+-[a-z]+$") || s.matches("^\\d+[a-z]+$")) {
            return true;
        }
        // sokuon symbol artifact like ~tsu -tsu etc
        if (s.contains("~tsu") || s.contains("-tsu") || s.contains("～tsu")
                || s.endsWith("~") || s.startsWith("~") || s.contains("～") || s.contains("~")) {
            return true;
        }
        // lone symbols or punctuation
        if (s.equals("-") || s.equals("~") || s.equals("～") || s.equals("^") || s.equals("_")) {
            return true;
        }
        // pinyin word leaking inside japanese song
        if (isMandarinPinyinToken(s)) {
            return true;
        }
        // lone double consonants like sokuon mora
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

        if (embedded != null && !embedded.isEmpty()) {
            for (int li = 0; li < embedded.size(); li++) {
                LyricsLine line = embedded.get(li);
                String text = line.text();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                totalChecked++;
                if (hasCjk(text)) {
                    cjkCount++;
                }

                LyricsLine origLine = (originalLines != null && li < originalLines.size()) ? originalLines.get(li) : null;
                String origText = origLine != null ? origLine.text() : null;

                // check weird symbol or number that not in original text
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
                        }
                    }
                }
                // many split single vowels mean mora is broken up
                if (singleLetterTokens >= 3 && origText != null && containsJapanese(origText)) {
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

        // check each word romaji if broken too
        if (perWord && originalLines != null) {
            int totalWordTokens = 0;
            int shortWordTokens = 0;
            int charWordTokens = 0;
            int singleWordTokens = 0;
            for (LyricsLine l : originalLines) {
                if (!l.hasWords()) continue;
                String origLineText = l.text();
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
                                }
                            }
                        }
                    }
                }
                if (singleWordTokens >= 3 && origLineText != null && containsJapanese(origLineText)) {
                    return true;
                }
            }
            if (totalWordTokens >= 2) {
                double avgWordLen = (double) charWordTokens / totalWordTokens;
                double shortWordRatio = (double) shortWordTokens / totalWordTokens;
                if (avgWordLen <= 2.6 || shortWordRatio > 0.35) {
                    return true;
                }
            }
        }

        // if word length too short or too many fragment then reject it
        if (totalTokens >= 2) {
            double avgLen = (double) totalChars / totalTokens;
            double shortRatio = (double) shortTokens / totalTokens;
            if (avgLen <= 2.6 || shortRatio > 0.35) {
                return true;
            }
        }

        return false;
    }

    private static boolean containsDigit(@Nullable String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean hasCjk(@Nullable CharSequence s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
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
                rWords.add(new Word(ow.startMs(), ow.endMs(), chunk, chunk, ow.endsWithSpace()));
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
            result.add(new LyricsLine(LyricsLine.NO_TIME, cleanJapaneseQuotes(stripParentheses(text)).trim()));
        }
        return result;
    }

    @NonNull
    public static List<String> romanizeOnline(@NonNull List<String> lines) {
        final String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        if (!"DEFAULT".equalsIgnoreCase(language)) {
            try {
                List<String> mapped = LyricsMerge.mapLinesOnline(lines,
                        l -> {
                            try {
                                return TextTranslator.romanize(l, language);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                if (mapped != null && !mapped.isEmpty()) {
                    return mapped;
                }
            } catch (Throwable ignored) {
            }
        }
        return romanizeTexts(lines);
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    public static boolean isKana(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA;
    }

    public static boolean isKatakana(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.KATAKANA || c == 'ー' || c == '・';
    }

    public static boolean isHiragana(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.HIRAGANA;
    }

    public static boolean isKanji(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    public static boolean isPureKana(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isKana(c) && !Character.isWhitespace(c) && c != 'ー' && c != '〜' && c != '~' && c != '、' && c != '。') {
                return false;
            }
        }
        return true;
    }

    public static boolean containsJapanese(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPurelyLatinScript(@Nullable CharSequence text) {
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

    public static boolean needsRomanization(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return false;
        return !isPurelyLatinScript(text);
    }

    public static String romanizeKana(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        int len = text.length();
        int i = 0;
        while (i < len) {
            char currentChar = text.charAt(i);
            if (currentChar == 'っ' || currentChar == 'ッ') {
                String nextRom = null;
                if (i + 2 <= len) {
                    String dig = text.substring(i + 1, Math.min(len, i + 3));
                    nextRom = KANA_DIGRAPHS.get(dig);
                }
                if (nextRom == null && i + 1 < len) {
                    nextRom = KANA_SINGLES.get(String.valueOf(text.charAt(i + 1)));
                }
                if (nextRom != null && !nextRom.isEmpty()) {
                    char firstLetter = nextRom.charAt(0);
                    result.append(firstLetter == 'c' ? 't' : firstLetter);
                    i++;
                    continue;
                } else {
                    i++;
                    continue;
                }
            }

            if (currentChar == 'ー') {
                char lastChar = result.length() > 0 ? Character.toLowerCase(result.charAt(result.length() - 1)) : ' ';
                if (lastChar == 'a' || lastChar == 'i' || lastChar == 'u' || lastChar == 'e' || lastChar == 'o') {
                    result.append(lastChar);
                } else {
                    result.append('-');
                }
                i++;
                continue;
            }

            if (i + 1 < len) {
                String dig = text.substring(i, i + 2);
                String rom = KANA_DIGRAPHS.get(dig);
                if (rom != null) {
                    result.append(rom);
                    i += 2;
                    continue;
                }
            }

            String singleRom = KANA_SINGLES.get(String.valueOf(currentChar));
            if (singleRom != null) {
                result.append(singleRom);
                i++;
                continue;
            }

            result.append(currentChar);
            i++;
        }
        return result.toString();
    }

    public static String romanizeJapanese(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        return romanizeKana(text);
    }

    public static List<String> extractRomajiMoras(@Nullable String romaji) {
        List<String> moras = new ArrayList<>();
        if (romaji == null || romaji.isEmpty()) return moras;
        String clean = romaji.trim().toLowerCase(Locale.ROOT);
        int len = clean.length();
        int i = 0;
        while (i < len) {
            char c = clean.charAt(i);
            if (c == ' ' || c == '\'' || c == '-') {
                i++;
                continue;
            }
            if (i + 1 < len && !isVowel(c) && c != 'n') {
                char next = clean.charAt(i + 1);
                if (c == next || (c == 't' && next == 'c')) {
                    moras.add(String.valueOf(c));
                    i++;
                    continue;
                }
            }
            if (c == 'n') {
                if (i + 1 == len || (!isVowel(clean.charAt(i + 1)) && clean.charAt(i + 1) != 'y')) {
                    moras.add("n");
                    i++;
                    continue;
                }
            }
            int start = i;
            while (i < len && !isVowel(clean.charAt(i)) && clean.charAt(i) != ' ' && clean.charAt(i) != '\'') {
                i++;
            }
            if (i < len && isVowel(clean.charAt(i))) {
                i++;
            }
            moras.add(clean.substring(start, i));
        }
        return moras;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o';
    }

    public static List<String> splitTokenReading(String surface, String reading) {
        if (surface == null || surface.isEmpty() || reading == null || reading.isEmpty()) {
            return Collections.emptyList();
        }
        if (surface.length() == 1) {
            return Collections.singletonList(reading);
        }

        if (isPureKana(surface)) {
            List<String> result = new ArrayList<>(surface.length());
            for (int i = 0; i < surface.length(); i++) {
                char c = surface.charAt(i);
                if (i + 1 < surface.length()) {
                    String dig = surface.substring(i, i + 2);
                    if (KANA_DIGRAPHS.containsKey(dig)) {
                        result.add(romanizeKana(dig));
                        i++;
                        continue;
                    }
                }
                result.add(romanizeKana(String.valueOf(c)));
            }
            return result;
        }

        int trailingKanaCount = 0;
        while (trailingKanaCount < surface.length()
                && isKana(surface.charAt(surface.length() - 1 - trailingKanaCount))) {
            trailingKanaCount++;
        }

        if (trailingKanaCount > 0) {
            String kanjiPart = surface.substring(0, surface.length() - trailingKanaCount);
            String kanaPart = surface.substring(surface.length() - trailingKanaCount);
            String kanaPartRomaji = romanizeKana(kanaPart).toLowerCase(Locale.ROOT);
            String cleanReading = reading.trim().toLowerCase(Locale.ROOT);
            if (cleanReading.endsWith(kanaPartRomaji)) {
                String kanjiReading = cleanReading.substring(0, cleanReading.length() - kanaPartRomaji.length());
                List<String> kanjiPartSplit = splitTokenReading(kanjiPart, kanjiReading);
                List<String> kanaSplit = new ArrayList<>(kanaPart.length());
                for (int i = 0; i < kanaPart.length(); i++) {
                    if (i + 1 < kanaPart.length() && KANA_DIGRAPHS.containsKey(kanaPart.substring(i, i + 2))) {
                        kanaSplit.add(romanizeKana(kanaPart.substring(i, i + 2)));
                        i++;
                    } else {
                        kanaSplit.add(romanizeKana(String.valueOf(kanaPart.charAt(i))));
                    }
                }
                List<String> combined = new ArrayList<>(kanjiPartSplit.size() + kanaSplit.size());
                combined.addAll(kanjiPartSplit);
                combined.addAll(kanaSplit);
                return combined;
            }
        }

        List<String> moras = extractRomajiMoras(reading);
        int numKanji = surface.length();
        int numMoras = moras.size();

        if (numKanji == 2) {
            if (numMoras == 2) {
                return List.of(moras.get(0), moras.get(1));
            }
            if (numMoras == 4) {
                return List.of(moras.get(0) + moras.get(1), moras.get(2) + moras.get(3));
            }
            if (numMoras == 3) {
                String lastMora = moras.get(2);
                if (lastMora.equals("i") || lastMora.equals("u") || lastMora.equals("n")
                        || lastMora.equals("tsu") || lastMora.equals("ku")) {
                    return List.of(moras.get(0), moras.get(1) + moras.get(2));
                } else if (moras.get(1).equals("n") || moras.get(1).equals("tsu")
                        || moras.get(1).equals("ku") || moras.get(1).equals("u")) {
                    return List.of(moras.get(0) + moras.get(1), moras.get(2));
                } else {
                    return List.of(moras.get(0), moras.get(1) + moras.get(2));
                }
            }
        }

        if (numKanji == 3 && numMoras == 6) {
            return List.of(moras.get(0) + moras.get(1), moras.get(2) + moras.get(3), moras.get(4) + moras.get(5));
        }
        if (numKanji == 3 && numMoras == 3) {
            return List.of(moras.get(0), moras.get(1), moras.get(2));
        }

        List<String> result = new ArrayList<>(numKanji);
        double morasPerChar = (double) numMoras / numKanji;
        int moraIdx = 0;
        for (int k = 0; k < numKanji; k++) {
            int nextMoraIdx = (int) Math.round((k + 1) * morasPerChar);
            nextMoraIdx = Math.min(numMoras, Math.max(moraIdx + 1, nextMoraIdx));
            if (k == numKanji - 1) nextMoraIdx = numMoras;
            StringBuilder sb = new StringBuilder();
            for (int m = moraIdx; m < nextMoraIdx; m++) {
                sb.append(moras.get(m));
            }
            result.add(sb.toString());
            moraIdx = nextMoraIdx;
        }
        return result;
    }

    public static List<String> alignJapaneseCharacters(@Nullable String lineText, @Nullable String romajiText) {
        if (lineText == null || lineText.isEmpty()) {
            return Collections.emptyList();
        }
        int textLen = lineText.length();
        List<String> result = new ArrayList<>(textLen);
        for (int i = 0; i < textLen; i++) {
            result.add("");
        }

        String cleanRoma = romajiText != null ? romajiText.trim() : "";
        if (cleanRoma.isEmpty()) {
            cleanRoma = romanizeJapanese(lineText);
        }
        if (cleanRoma.isEmpty()) {
            return result;
        }

        String[] rawTokens = cleanRoma.split("\\s+");
        List<String> tokens = new ArrayList<>(rawTokens.length);
        for (String t : rawTokens) {
            String tr = t.trim();
            if (!tr.isEmpty()) {
                tokens.add(tr);
            }
        }
        if (tokens.isEmpty()) {
            return result;
        }

        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while (i < textLen) {
            char c = lineText.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (!isKana(c) && !isKanji(c)) {
                while (i < textLen && !Character.isWhitespace(lineText.charAt(i))
                        && !isKana(lineText.charAt(i)) && !isKanji(lineText.charAt(i))) {
                    i++;
                }
            } else if (isKanji(c)) {
                while (i < textLen && isKanji(lineText.charAt(i))) {
                    i++;
                }
                while (i < textLen && isKana(lineText.charAt(i))) {
                    i++;
                }
            } else {
                while (i < textLen && isKana(lineText.charAt(i))) {
                    i++;
                }
            }
            spans.add(new int[]{start, i});
        }

        if (spans.isEmpty()) {
            return result;
        }

        int numSpans = spans.size();
        int numTokens = tokens.size();
        List<String> spanRomaji = new ArrayList<>(numSpans);

        if (numTokens == numSpans) {
            spanRomaji.addAll(tokens);
        } else if (numTokens > numSpans) {
            double[] weights = new double[numSpans];
            for (int s = 0; s < numSpans; s++) {
                int[] span = spans.get(s);
                weights[s] = Math.max(1, span[1] - span[0]);
            }
            int[] groupCounts = distributeCounts(numTokens, numSpans, weights);
            int tCursor = 0;
            for (int s = 0; s < numSpans; s++) {
                int cnt = groupCounts[s];
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < cnt && tCursor < numTokens; c++) {
                    sb.append(tokens.get(tCursor++));
                }
                spanRomaji.add(sb.toString());
            }
        } else {
            double[] weights = new double[numTokens];
            for (int t = 0; t < numTokens; t++) {
                weights[t] = Math.max(1, tokens.get(t).length());
            }
            int[] groupCounts = distributeCounts(numSpans, numTokens, weights);
            int sCursor = 0;
            for (int t = 0; t < numTokens; t++) {
                int cnt = groupCounts[t];
                String tok = tokens.get(t);
                if (cnt <= 1) {
                    spanRomaji.add(tok);
                    sCursor++;
                } else {
                    int firstSpan = sCursor;
                    int lastSpan = Math.min(numSpans - 1, sCursor + cnt - 1);
                    StringBuilder combinedSpanText = new StringBuilder();
                    for (int sc = firstSpan; sc <= lastSpan; sc++) {
                        combinedSpanText.append(lineText, spans.get(sc)[0], spans.get(sc)[1]);
                    }
                    List<String> subSplit = splitTokenReading(combinedSpanText.toString(), tok);
                    int charOffset = 0;
                    for (int sc = firstSpan; sc <= lastSpan; sc++) {
                        int sLen = spans.get(sc)[1] - spans.get(sc)[0];
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < sLen && charOffset < subSplit.size(); k++) {
                            sb.append(subSplit.get(charOffset++));
                        }
                        spanRomaji.add(sb.toString());
                    }
                    sCursor = lastSpan + 1;
                }
            }
        }

        for (int s = 0; s < spans.size() && s < spanRomaji.size(); s++) {
            int[] span = spans.get(s);
            int start = span[0];
            int end = span[1];
            String spanText = lineText.substring(start, end);
            String rText = spanRomaji.get(s);

            if (end - start == 1) {
                result.set(start, rText);
            } else {
                List<String> charSplit = splitTokenReading(spanText, rText);
                for (int ci = 0; ci < end - start; ci++) {
                    String cr = (ci < charSplit.size()) ? charSplit.get(ci) : "";
                    result.set(start + ci, cr);
                }
            }
        }

        return result;
    }

    private static int[] distributeCounts(int total, int groups, double[] weights) {
        int[] counts = new int[groups];
        if (groups <= 0) return counts;
        if (total <= groups) {
            for (int i = 0; i < total; i++) counts[i] = 1;
            return counts;
        }
        double sumWeight = 0;
        for (double w : weights) sumWeight += w;
        if (sumWeight <= 0) sumWeight = groups;
        int allocated = 0;
        for (int i = 0; i < groups; i++) {
            int c = Math.max(1, (int) Math.round((weights[i] / sumWeight) * total));
            counts[i] = c;
            allocated += c;
        }
        while (allocated > total) {
            int maxIdx = 0;
            for (int i = 1; i < groups; i++) {
                if (counts[i] > counts[maxIdx]) maxIdx = i;
            }
            if (counts[maxIdx] <= 1) break;
            counts[maxIdx]--;
            allocated--;
        }
        while (allocated < total) {
            counts[groups - 1]++;
            allocated++;
        }
        return counts;
    }

    public static final class JapaneseSegment {
        public final int start;
        public final int end;
        public final String text;
        public final boolean isParticle;
        public final boolean isKatakana;

        public JapaneseSegment(int start, int end, String text, boolean isParticle, boolean isKatakana) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.isParticle = isParticle;
            this.isKatakana = isKatakana;
        }
    }

    private static final String[] COMMON_HIRAGANA_WORDS = {
            "いってらっしゃい", "いってきます", "ありがとうございます", "ごめんなさい",
            "あいにきて", "あいにいく", "会いにきて", "会いにいく",
            "ありがとう", "さようなら", "こんにちは", "おはよう",
            "おかえり", "ただいま", "お願い", "おねがい",
            "みたいだね", "みたいだ", "みたいね", "みたい",
            "なんて", "まるで", "だけど", "だから", "なのに", "なんだし", "なんだ", "るんだし", "るんだ",
            "ている", "ていた", "ていく", "てきて", "てしま", "ちゃう", "じゃう", "ちゃった", "じゃった",
            "じゃない", "ではない", "らしい", "そうだ", "ようだ", "ような", "ように",
            "どうして", "けれども", "それから", "どんなに", "ここのつ",
            "ちゃんと", "そして", "けれど",
            "その", "この", "あの", "どの", "それ", "これ", "あれ", "どれ",
            "そこ", "ここ", "あそこ", "どこ", "そんな", "こんな", "あんな", "どんな",
            "そっち", "こっち", "あっち", "どっち",
            "もっと", "ずっと", "きっと", "あのね", "いつも", "とても",
            "すこし", "少し", "たくさん", "いっぱい", "ぜんぶ",
            "ひとり", "ふたり", "みんな", "あなた", "わたし",
            "ひとつ", "ふたつ", "みっつ", "よっつ", "いつつ", "むっつ", "ななつ", "やっつ",
            "ぐらい", "くらい", "ばかり",
            "こと", "なぜ", "まだ", "また", "もう", "そう", "こう", "ああ",
            "いい", "ない", "ある", "いる", "する", "なる", "よう",
            "まま", "たび", "ごと", "ごとの", "ごとに",
            "だけ", "ほど", "など", "でも", "とお", "ぼく", "おれ", "きみ",
            "だれ", "なに", "いつ", "どう",
            "はい", "いいえ",
            "から", "まで", "より", "へと", "には", "では", "とは", "にも", "でも", "へも", "とも",
            "かも", "かな", "かね", "かしら", "わね", "よね", "だね", "だよ", "だぜ", "だろ", "だろう", "でしょう"
    };

    public static List<JapaneseSegment> segmentJapaneseLine(@Nullable String lineText) {
        if (lineText == null || lineText.isEmpty()) {
            return Collections.emptyList();
        }
        List<JapaneseSegment> segments = new ArrayList<>();
        int len = lineText.length();
        int i = 0;

        while (i < len) {
            char c = lineText.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            int start = i;

            if (isKatakana(c)) {
                while (i < len && isKatakana(lineText.charAt(i))) {
                    i++;
                }
                segments.add(new JapaneseSegment(start, i, lineText.substring(start, i), false, true));
                continue;
            }

            // check honorific prefix like o or go with kanji
            boolean isHonorificPrefix = (c == 'お' || c == 'ご') && (i + 1 < len && isKanji(lineText.charAt(i + 1)));
            boolean isDigitKanji = Character.isDigit(c) && i + 1 < len && isKanji(lineText.charAt(i + 1));
            if (isKanji(c) || isDigitKanji || isHonorificPrefix) {
                if (isHonorificPrefix) {
                    i++; // put o or go in kanji segment
                }
                while (i < len && (isKanji(lineText.charAt(i)) || Character.isDigit(lineText.charAt(i)))) {
                    i++;
                }
                // see if okurigana hiragana follow after kanji
                if (i < len && isHiragana(lineText.charAt(i))) {
                    char firstHira = lineText.charAt(i);
                    boolean isParticleNext = isSingleCharParticle(firstHira);
                    boolean isNextSokuon = (i + 1 < len && (lineText.charAt(i + 1) == 'っ' || lineText.charAt(i + 1) == 'ッ'));
                    boolean particleTerminated = (i + 1 >= len)
                            || Character.isWhitespace(lineText.charAt(i + 1))
                            || isPunctuation(lineText.charAt(i + 1))
                            || isKanji(lineText.charAt(i + 1))
                            || isKatakana(lineText.charAt(i + 1));

                    if (isParticleNext && !isNextSokuon && particleTerminated) {
                        // noun stop here so next particle can split cleanly
                        segments.add(new JapaneseSegment(start, i, lineText.substring(start, i), false, false));
                        continue;
                    }

                    // okurigana verb or adjective ending
                    while (i < len && isHiragana(lineText.charAt(i))) {
                        if (i > start) {
                            boolean isSeparateWord = false;
                            for (String kw : COMMON_HIRAGANA_WORDS) {
                                if (lineText.startsWith(kw, i)) {
                                    if (kw.equals("そんな") || kw.equals("こんな") || kw.equals("あんな") || kw.equals("どんな")
                                            || kw.equals("もっと") || kw.equals("ずっと") || kw.equals("きっと")
                                            || kw.equals("そして") || kw.equals("だけど") || kw.equals("だから")
                                            || kw.equals("いつも") || kw.equals("とても") || kw.equals("まるで")) {
                                        isSeparateWord = true;
                                        break;
                                    }
                                }
                            }
                            if (isSeparateWord) {
                                break;
                            }
                        }
                        char h = lineText.charAt(i);
                        char prevChar = (i > 0) ? lineText.charAt(i - 1) : 0;
                        boolean prevHatsuOrSoku = (prevChar == 'ん' || prevChar == 'っ' || prevChar == 'ッ');
                        boolean curNextSokuon = (i + 1 < len && (lineText.charAt(i + 1) == 'っ' || lineText.charAt(i + 1) == 'ッ'));
                        boolean isVerbEnding = (h == 'で' || h == 'て' || h == 'た' || h == 'だ' || h == 'ば' || h == 'ず' || h == 'い');

                        if (isSingleCharParticle(h) && !prevHatsuOrSoku && !curNextSokuon && !isVerbEnding && i > start) {
                            boolean term = (i + 1 >= len)
                                    || Character.isWhitespace(lineText.charAt(i + 1))
                                    || isPunctuation(lineText.charAt(i + 1))
                                    || isKanji(lineText.charAt(i + 1))
                                    || isKatakana(lineText.charAt(i + 1));
                            if (term) {
                                break;
                            }
                        }
                        i++;
                    }
                }
                segments.add(new JapaneseSegment(start, i, lineText.substring(start, i), false, false));
                continue;
            }

            // hiragana word or particle
            if (isHiragana(c)) {
                // match common hiragana word or adverb
                String matchedCommonWord = null;
                for (String word : COMMON_HIRAGANA_WORDS) {
                    if (lineText.startsWith(word, i)) {
                        matchedCommonWord = word;
                        break;
                    }
                }
                if (matchedCommonWord != null) {
                    i += matchedCommonWord.length();
                    segments.add(new JapaneseSegment(start, i, matchedCommonWord, false, false));
                    continue;
                }

                // check 2 char particle like kara made yori
                if (i + 1 < len) {
                    String twoChar = lineText.substring(i, i + 2);
                    if (twoChar.equals("から") || twoChar.equals("まで") || twoChar.equals("より")
                            || twoChar.equals("へと") || twoChar.equals("には") || twoChar.equals("では")) {
                        boolean term = (i + 2 >= len)
                                || Character.isWhitespace(lineText.charAt(i + 2))
                                || isPunctuation(lineText.charAt(i + 2))
                                || isKanji(lineText.charAt(i + 2))
                                || isKatakana(lineText.charAt(i + 2));
                        if (term) {
                            i += 2;
                            segments.add(new JapaneseSegment(start, i, twoChar, true, false));
                            continue;
                        }
                    }
                }

                // check 1 char particle like wa ga wo ni
                boolean isPrevSokuon = (i > 0 && (lineText.charAt(i - 1) == 'っ' || lineText.charAt(i - 1) == 'ッ' || lineText.charAt(i - 1) == 'ん'));
                boolean isNextSokuon = (i + 1 < len && (lineText.charAt(i + 1) == 'っ' || lineText.charAt(i + 1) == 'ッ'));
                if (isSingleCharParticle(c) && !isPrevSokuon && !isNextSokuon) {
                    boolean term = (i + 1 >= len)
                            || Character.isWhitespace(lineText.charAt(i + 1))
                            || isPunctuation(lineText.charAt(i + 1))
                            || isKanji(lineText.charAt(i + 1))
                            || isKatakana(lineText.charAt(i + 1))
                            || (i + 1 < len && isSingleCharParticle(lineText.charAt(i + 1)));
                    if (term) {
                        i++;
                        segments.add(new JapaneseSegment(start, i, String.valueOf(c), true, false));
                        continue;
                    }
                }

                // grab other hiragana until meet kanji or space
                while (i < len && isHiragana(lineText.charAt(i))) {
                    char curH = lineText.charAt(i);
                    char curPrev = (i > start) ? lineText.charAt(i - 1) : 0;
                    boolean curPrevHatsuOrSoku = (curPrev == 'ん' || curPrev == 'っ' || curPrev == 'ッ');
                    boolean curNextSokuon = (i + 1 < len && (lineText.charAt(i + 1) == 'っ' || lineText.charAt(i + 1) == 'ッ'));
                    boolean isVerbEnding = (curH == 'で' || curH == 'て' || curH == 'た' || curH == 'だ' || curH == 'ば' || curH == 'ず' || curH == 'い');
                    if (isSingleCharParticle(curH) && !curPrevHatsuOrSoku && !curNextSokuon && !isVerbEnding && i > start) {
                        boolean term = (i + 1 >= len) || Character.isWhitespace(lineText.charAt(i + 1))
                                || isPunctuation(lineText.charAt(i + 1))
                                || isKanji(lineText.charAt(i + 1))
                                || isKatakana(lineText.charAt(i + 1));
                        if (term) {
                            break;
                        }
                    }
                    i++;
                }
                segments.add(new JapaneseSegment(start, i, lineText.substring(start, i), false, false));
                continue;
            }

            // other stuff like latin letter or number
            while (i < len && !Character.isWhitespace(lineText.charAt(i))
                    && !isKana(lineText.charAt(i)) && !isKanji(lineText.charAt(i))) {
                i++;
            }
            if (i > start) {
                segments.add(new JapaneseSegment(start, i, lineText.substring(start, i), false, false));
            }
        }

        return segments;
    }

    public static boolean isSingleCharParticle(char c) {
        return c == 'は' || c == 'が' || c == 'を' || c == 'に' || c == 'へ'
                || c == 'で' || c == 'と' || c == 'も' || c == 'ね' || c == 'よ'
                || c == 'か' || c == 'の' || c == 'な' || c == 'だ';
    }

    public static boolean isPunctuation(char c) {
        return c == '、' || c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                || c == ',' || c == '.' || c == '~' || c == '～' || c == '…'
                || c == '—' || c == '–'
                || c == '『' || c == '』' || c == '「' || c == '」'
                || c == '〈' || c == '〉' || c == '《' || c == '》'
                || c == '〔' || c == '〕' || c == '【' || c == '】'
                || c == '(' || c == ')' || c == '（' || c == '）'
                || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '“' || c == '”' || c == '"' || c == '\''
                || c == '«' || c == '»' || c == '〝' || c == '〞';
    }

    public static String getParticleRomazi(String p) {
        if (p == null) return "";
        return switch (p) {
            case "は" -> "wa";
            case "を" -> "o";
            case "へ" -> "e";
            case "で" -> "de";
            case "に" -> "ni";
            case "が" -> "ga";
            case "と" -> "to";
            case "も" -> "mo";
            case "ね" -> "ne";
            case "よ" -> "yo";
            case "か" -> "ka";
            case "の" -> "no";
            case "な" -> "na";
            case "だ" -> "da";
            case "から" -> "kara";
            case "まで" -> "made";
            case "より" -> "yori";
            default -> romanizeKana(p);
        };
    }

    @NonNull
    public static String getSegmentReading(JapaneseSegment seg) {
        if (seg.isParticle) {
            return getParticleRomazi(seg.text);
        }
        if (seg.isKatakana || isPureKana(seg.text)) {
            return romanizeKana(seg.text);
        }
        String rom = romanizeJapanese(seg.text);
        return (rom != null && !rom.isEmpty()) ? rom : seg.text;
    }

    public static int levenshteinDistance(String s1, String s2) {
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

    private static int segmentCost(String expClean, List<String> cleanTokens, int a, int b) {
        if (a == b) {
            return Math.max(4, expClean.length() * 2);
        }
        StringBuilder sb = new StringBuilder();
        for (int k = a; k < b; k++) {
            sb.append(cleanTokens.get(k));
        }
        String combined = sb.toString();
        if (combined.equals(expClean)) {
            return 0;
        }
        return levenshteinDistance(combined, expClean);
    }

    public static List<String> alignJapaneseSegments(List<JapaneseSegment> segments, @Nullable String normLine) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        int numSegs = segments.size();
        List<String> expectedList = new ArrayList<>(numSegs);
        List<String> cleanExpected = new ArrayList<>(numSegs);

        for (int i = 0; i < numSegs; i++) {
            JapaneseSegment seg = segments.get(i);
            String r = getSegmentReading(seg);
            r = (r != null) ? r.trim() : "";
            expectedList.add(r);
            cleanExpected.add(r.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT));
        }

        String cleanRoma = normLine != null ? normLine.trim() : "";
        String[] rawTokens = cleanRoma.isEmpty() ? new String[0] : cleanRoma.split("\\s+");
        List<String> tokens = new ArrayList<>(rawTokens.length);
        List<String> cleanTokens = new ArrayList<>(rawTokens.length);
        for (String t : rawTokens) {
            String tr = t.trim();
            if (!tr.isEmpty()) {
                tokens.add(tr);
                cleanTokens.add(tr.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT));
            }
        }

        int numTokens = tokens.size();
        if (numTokens == 0) {
            return expectedList;
        }
        if (numTokens == 1 && numSegs > 1) {
            return expectedList;
        }

        if (numTokens == numSegs) {
            List<String> readings = new ArrayList<>(numSegs);
            for (int i = 0; i < numSegs; i++) {
                String tok = tokens.get(i);
                if (tok != null && !tok.isEmpty() && isPurelyLatinScript(tok)) {
                    readings.add(tok);
                } else {
                    readings.add(expectedList.get(i));
                }
            }
            return readings;
        }

        // dp partition to split tokens into segments
        int[][] dp = new int[numSegs + 1][numTokens + 1];
        int[][] choice = new int[numSegs + 1][numTokens + 1];

        for (int j = 1; j <= numTokens; j++) {
            dp[0][j] = j * 10;
        }

        for (int i = 1; i <= numSegs; i++) {
            String expClean = cleanExpected.get(i - 1);
            for (int j = 0; j <= numTokens; j++) {
                int bestCost = Integer.MAX_VALUE;
                int bestK = 0;
                for (int k = 0; k <= j; k++) {
                    int prevCost = dp[i - 1][k];
                    if (prevCost == Integer.MAX_VALUE) continue;
                    int cost = prevCost + segmentCost(expClean, cleanTokens, k, j);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestK = k;
                    }
                }
                dp[i][j] = bestCost;
                choice[i][j] = bestK;
            }
        }

        // backtrack path
        int[] partition = new int[numSegs + 1];
        partition[numSegs] = numTokens;
        for (int i = numSegs; i >= 1; i--) {
            partition[i - 1] = choice[i][partition[i]];
        }

        List<String> readings = new ArrayList<>(numSegs);
        for (int i = 0; i < numSegs; i++) {
            readings.add(expectedList.get(i));
        }

        int segIdx = 0;
        while (segIdx < numSegs) {
            int firstSeg = segIdx;
            int a = Math.max(0, Math.min(numTokens, partition[firstSeg]));
            int lastSeg = firstSeg;
            while (lastSeg + 1 < numSegs && partition[lastSeg + 1] == a) {
                lastSeg++;
            }
            int b = Math.max(a, Math.min(numTokens, partition[Math.min(numSegs, lastSeg + 1)]));

            if (b > a) {
                StringBuilder sb = new StringBuilder();
                StringBuilder noSpaceSb = new StringBuilder();
                for (int k = a; k < b && k < tokens.size(); k++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(tokens.get(k));
                    noSpaceSb.append(tokens.get(k));
                }
                String exp = (firstSeg < expectedList.size()) ? expectedList.get(firstSeg) : "";
                String joined;
                if (!exp.contains(" ") && (b - a > 1)) {
                    joined = noSpaceSb.toString().trim();
                } else {
                    joined = sb.toString().trim();
                }

                int groupCount = lastSeg - firstSeg + 1;
                if (groupCount == 1) {
                    readings.set(firstSeg, !joined.isEmpty() ? joined : expectedList.get(firstSeg));
                } else {
                    StringBuilder combinedText = new StringBuilder();
                    for (int s = firstSeg; s <= lastSeg; s++) {
                        combinedText.append(segments.get(s).text);
                    }
                    List<String> splitReadings = splitTokenReading(combinedText.toString(), joined);
                    if (splitReadings.size() == groupCount) {
                        for (int s = 0; s < groupCount; s++) {
                            readings.set(firstSeg + s, splitReadings.get(s));
                        }
                    } else {
                        int charOffset = 0;
                        for (int s = firstSeg; s <= lastSeg; s++) {
                            int segLen = segments.get(s).text.length();
                            StringBuilder segSb = new StringBuilder();
                            for (int k = 0; k < segLen && charOffset < splitReadings.size(); k++) {
                                segSb.append(splitReadings.get(charOffset++));
                            }
                            String segR = segSb.toString().trim();
                            readings.set(s, !segR.isEmpty() ? segR : expectedList.get(s));
                        }
                    }
                }
            }

            segIdx = lastSeg + 1;
        }

        return readings;
    }

    private static Map<String, String> createKanaDigraphs() {
        Map<String, String> m = new HashMap<>();
        m.put("きゃ", "kya"); m.put("きゅ", "kyu"); m.put("きょ", "kyo");
        m.put("しゃ", "sha"); m.put("しゅ", "shu"); m.put("しょ", "sho");
        m.put("ちゃ", "cha"); m.put("ちゅ", "chu"); m.put("ちょ", "cho");
        m.put("にゃ", "nya"); m.put("にゅ", "nyu"); m.put("にょ", "nyo");
        m.put("ひゃ", "hya"); m.put("ひゅ", "hyu"); m.put("ひょ", "hyo");
        m.put("みゃ", "mya"); m.put("みゅ", "myu"); m.put("みょ", "myo");
        m.put("りゃ", "rya"); m.put("りゅ", "ryu"); m.put("りょ", "ryo");
        m.put("ぎゃ", "gya"); m.put("ぎゅ", "gyu"); m.put("ぎょ", "gyo");
        m.put("じゃ", "ja");  m.put("じゅ", "ju");  m.put("じょ", "jo");
        m.put("ぢゃ", "ja");  m.put("ぢゅ", "ju");  m.put("ぢょ", "jo");
        m.put("びゃ", "bya"); m.put("びゅ", "byu"); m.put("びょ", "byo");
        m.put("ぴゃ", "pya"); m.put("ぴゅ", "pyu"); m.put("ぴょ", "pyo");
        m.put("くゎ", "kwa"); m.put("ぐゎ", "gwa");

        m.put("キャ", "kya"); m.put("キュ", "kyu"); m.put("キョ", "kyo");
        m.put("シャ", "sha"); m.put("シュ", "shu"); m.put("ショ", "sho");
        m.put("チャ", "cha"); m.put("チュ", "chu"); m.put("チョ", "cho");
        m.put("ニャ", "nya"); m.put("ニュ", "nyu"); m.put("ニョ", "nyo");
        m.put("ヒャ", "hya"); m.put("ヒュ", "hyu"); m.put("ヒョ", "hyo");
        m.put("ミャ", "mya"); m.put("ミュ", "myu"); m.put("ミョ", "myo");
        m.put("リャ", "rya"); m.put("リュ", "ryu"); m.put("リョ", "ryo");
        m.put("ギャ", "gya"); m.put("ギュ", "gyu"); m.put("ギョ", "gyo");
        m.put("ジャ", "ja");  m.put("ジュ", "ju");  m.put("ジョ", "jo");
        m.put("ヂャ", "ja");  m.put("ヂュ", "ju");  m.put("ヂョ", "jo");
        m.put("ビャ", "bya"); m.put("ビュ", "byu"); m.put("ビョ", "byo");
        m.put("ピャ", "pya"); m.put("ピュ", "pyu"); m.put("ピョ", "pyo");
        m.put("ファ", "fa");  m.put("フィ", "fi");  m.put("フェ", "fe");  m.put("フォ", "fo");
        m.put("ティ", "ti");  m.put("ディ", "di");  m.put("トゥ", "tu");  m.put("ドゥ", "du");
        m.put("ウィ", "wi");  m.put("ウェ", "we");  m.put("ウォ", "wo");
        m.put("ヴァ", "va");  m.put("ヴィ", "vi");  m.put("ヴェ", "ve");  m.put("ヴォ", "vo");
        m.put("シェ", "she"); m.put("ジェ", "je");  m.put("チェ", "che"); m.put("ツァ", "tsa");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> createKanaSingles() {
        Map<String, String> m = new HashMap<>();
        m.put("あ", "a"); m.put("い", "i"); m.put("う", "u"); m.put("え", "e"); m.put("お", "o");
        m.put("か", "ka"); m.put("き", "ki"); m.put("く", "ku"); m.put("け", "ke"); m.put("こ", "ko");
        m.put("さ", "sa"); m.put("し", "shi"); m.put("す", "su"); m.put("せ", "se"); m.put("そ", "so");
        m.put("た", "ta"); m.put("ち", "chi"); m.put("つ", "tsu"); m.put("て", "te"); m.put("と", "to");
        m.put("な", "na"); m.put("に", "ni"); m.put("ぬ", "nu"); m.put("ね", "ne"); m.put("の", "no");
        m.put("は", "ha"); m.put("ひ", "hi"); m.put("ふ", "fu"); m.put("へ", "he"); m.put("ほ", "ho");
        m.put("ま", "ma"); m.put("み", "mi"); m.put("む", "mu"); m.put("め", "me"); m.put("も", "mo");
        m.put("や", "ya"); m.put("ゆ", "yu"); m.put("よ", "yo");
        m.put("ら", "ra"); m.put("り", "ri"); m.put("る", "ru"); m.put("れ", "re"); m.put("ろ", "ro");
        m.put("わ", "wa"); m.put("ゐ", "wi"); m.put("ゑ", "we"); m.put("を", "o"); m.put("ん", "n");
        m.put("が", "ga"); m.put("ぎ", "gi"); m.put("ぐ", "gu"); m.put("げ", "ge"); m.put("ご", "go");
        m.put("ざ", "za"); m.put("じ", "ji"); m.put("ず", "zu"); m.put("ぜ", "ze"); m.put("ぞ", "zo");
        m.put("だ", "da"); m.put("ぢ", "ji"); m.put("づ", "zu"); m.put("で", "de"); m.put("ど", "do");
        m.put("ば", "ba"); m.put("び", "bi"); m.put("ぶ", "bu"); m.put("べ", "be"); m.put("ぼ", "bo");
        m.put("ぱ", "pa"); m.put("ぴ", "pi"); m.put("ぷ", "pu"); m.put("ペ", "pe"); m.put("ぽ", "po");
        m.put("ぁ", "a"); m.put("ぃ", "i"); m.put("ぅ", "u"); m.put("ぇ", "e"); m.put("ぉ", "o");
        m.put("ゃ", "ya"); m.put("ゅ", "yu"); m.put("ょ", "yo"); m.put("ゎ", "wa");

        m.put("ア", "a"); m.put("イ", "i"); m.put("ウ", "u"); m.put("エ", "e"); m.put("オ", "o");
        m.put("カ", "ka"); m.put("キ", "ki"); m.put("ク", "ku"); m.put("ケ", "ke"); m.put("コ", "ko");
        m.put("サ", "sa"); m.put("シ", "shi"); m.put("ス", "su"); m.put("セ", "se"); m.put("ソ", "so");
        m.put("タ", "ta"); m.put("チ", "chi"); m.put("ツ", "tsu"); m.put("テ", "te"); m.put("ト", "to");
        m.put("ナ", "na"); m.put("ニ", "ni"); m.put("ヌ", "nu"); m.put("ネ", "ne"); m.put("ノ", "no");
        m.put("ハ", "ha"); m.put("ヒ", "hi"); m.put("フ", "fu"); m.put("ヘ", "he"); m.put("ホ", "ho");
        m.put("マ", "ma"); m.put("ミ", "mi"); m.put("ム", "mu"); m.put("メ", "me"); m.put("モ", "mo");
        m.put("ヤ", "ya"); m.put("ユ", "yu"); m.put("ヨ", "yo");
        m.put("ラ", "ra"); m.put("リ", "ri"); m.put("ル", "ru"); m.put("レ", "re"); m.put("ロ", "ro");
        m.put("ワ", "wa"); m.put("ヰ", "wi"); m.put("ヱ", "we"); m.put("ヲ", "o"); m.put("ン", "n");
        m.put("ガ", "ga"); m.put("ギ", "gi"); m.put("グ", "gu"); m.put("ゲ", "ge"); m.put("ゴ", "go");
        m.put("ザ", "za"); m.put("ジ", "ji"); m.put("ズ", "zu"); m.put("ゼ", "ze"); m.put("ゾ", "zo");
        m.put("ダ", "da"); m.put("ヂ", "ji"); m.put("ヅ", "zu"); m.put("デ", "de"); m.put("ド", "do");
        m.put("バ", "ba"); m.put("ビ", "bi"); m.put("ブ", "bu"); m.put("ベ", "be"); m.put("ボ", "bo");
        m.put("パ", "pa"); m.put("ピ", "pi"); m.put("プ", "pu"); m.put("ペ", "pe"); m.put("ポ", "po");
        m.put("ァ", "a"); m.put("ィ", "i"); m.put("ゥ", "u"); m.put("ェ", "e"); m.put("ォ", "o");
        m.put("ャ", "ya"); m.put("ュ", "yu"); m.put("ョ", "yo"); m.put("ヮ", "wa");
        m.put("ヴ", "vu");
        return Collections.unmodifiableMap(m);
    }


    @NonNull
    public static List<String> alignRomanizationAnchors(
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
            if (guide == null || guide.trim().isEmpty() || hasCjk(guide)) {
                if (isPurelyLatinScript(text)) {
                    guide = text.trim();
                } else if (containsJapanese(text)) {
                    guide = romanizeJapanese(text).trim();
                } else if (containsHangul(text)) {
                    guide = romanizeHangul(text).trim();
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

    @NonNull
    public static List<String> alignRomanizationAnchors(
            @Nullable String fullText,
            @Nullable List<Word> origWords) {
        return alignRomanizationAnchors(origWords, fullText);
    }

    /**
     * Partitions fullText into M segments that phonetically match the guides while preserving spaces.
     */
    @NonNull
    public static List<String> alignRomanizationAnchors(
            @NonNull String fullText,
            @NonNull List<String> romanizedGuides,
            @NonNull List<String> originalSyllables) {
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

            // latin use strict length but asian text need flexible length
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

        // if cannot reach end just pick best endpoint
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

        // put leftover chars to first or last piece
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

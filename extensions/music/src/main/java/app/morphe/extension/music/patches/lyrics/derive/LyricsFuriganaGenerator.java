/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.romanizers.WordReadingTable;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class LyricsFuriganaGenerator {

    public static final String DEFAULT_AI_FURIGANA_PROMPT =
            "Untuk setiap baris lirik bahasa Jepang, tambahkan furigana dalam tanda kurung siku tepat setelah kanji, "
                    + "contoh: 漢字[かんじ]. Jangan ubah kata kana biasa. Output harus persis sama jumlah barisnya.";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-furigana-generator");
        thread.setDaemon(true);
        return thread;
    });

    private static final Pattern BRACKET_RUBY_PATTERN = Pattern.compile("([^\\s\\[\\]]+)\\[([^\\[\\]]+)\\]");


    public interface Callback {
        void onGenerated(@Nullable Lyrics lyrics, boolean fromAI, @Nullable String model);
    }

    private LyricsFuriganaGenerator() {
    }

    /**
     * Synchronously applies embedded ruby formatting and local dictionary furigana
     * so the UI renders furigana on the very first pass without waiting for an async thread.
     */
    @Nullable
    public static Lyrics generateLocalSync(@Nullable Lyrics lyrics) {
        if (lyrics == null || lyrics.lines() == null || lyrics.lines().isEmpty()) {
            return lyrics;
        }
        if (!hasJapaneseOrRuby(lyrics.lines())) {
            return lyrics;
        }
        final List<String> providers = AiProviders.enabledProviders(
                Settings.LYRICS_FURIGANA_PROVIDERS.get(),
                AiProviders.FURIGANA_ORDER);
        if (providers.isEmpty()) {
            return lyrics;
        }
        final String targetStyle = Settings.LYRICS_FURIGANA_STYLE.get();
        Lyrics current = lyrics;
        if (providers.contains(AiProviders.EMBEDDED)) {
            current = formatExistingRuby(current, targetStyle);
        }
        if (providers.contains(AiProviders.LOCAL)) {
            current = enrichFromLocalDict(current, targetStyle);
        }
        return current;
    }

    /**
     * Enriches a single line on demand (used when LyricsSpanBuilder processes a line that has not
     * yet been segmented into words or is missing Kanji furigana).
     */
    public static LyricsLine enrichLineForDisplay(LyricsLine line, long fallbackStartMs, long fallbackEndMs) {
        if (line == null || line.text() == null || line.text().isEmpty()) {
            return line;
        }
        final String targetStyle = Settings.LYRICS_FURIGANA_STYLE.get();
        return enrichSingleLineLocal(line, JapaneseDictStore.table(), targetStyle, fallbackStartMs, fallbackEndMs);
    }

    public static void generate(TrackInfo track, Lyrics lyrics, Callback callback) {
        if (lyrics == null || lyrics.lines() == null || lyrics.lines().isEmpty()) {
            callback.onGenerated(lyrics, false, null);
            return;
        }

        if (!hasJapaneseOrRuby(lyrics.lines())) {
            callback.onGenerated(lyrics, false, null);
            return;
        }

        final List<String> providers = AiProviders.enabledProviders(
                Settings.LYRICS_FURIGANA_PROVIDERS.get(),
                AiProviders.FURIGANA_ORDER);

        if (providers.isEmpty()) {
            callback.onGenerated(lyrics, false, null);
            return;
        }

        final String targetStyle = Settings.LYRICS_FURIGANA_STYLE.get();

        // First perform synchronous local + embedded pass so known Kanji readings are ready
        Lyrics localPass = generateLocalSync(lyrics);
        if (localPass == null) {
            localPass = lyrics;
        }

        boolean needsAsync = false;
        for (String provider : providers) {
            if (AiProviders.isAi(provider)) {
                needsAsync = true;
                break;
            }
            if (AiProviders.LOCAL.equals(provider) && !JapaneseDictStore.isReady()) {
                needsAsync = true;
                break;
            }
        }

        if (!needsAsync) {
            callback.onGenerated(localPass, false, null);
            return;
        }

        // Immediately deliver the synchronous local pass so Kanji furigana
        // appears right away while any async dictionary download runs in the background.
        if (localPass != lyrics) {
            callback.onGenerated(localPass, false, null);
        }

        final Lyrics startLyrics = localPass;
        WORKER.execute(() -> {
            try {
                Lyrics currentLyrics = startLyrics;
                boolean fromAi = false;
                String aiModel = null;

                for (String provider : providers) {
                    if (AiProviders.EMBEDDED.equals(provider)) {
                        currentLyrics = formatExistingRuby(currentLyrics, targetStyle);
                    } else if (AiProviders.LOCAL.equals(provider)) {
                        if (!JapaneseDictStore.isReady()) {
                            JapaneseDictStore.ensure(null);
                        }
                        currentLyrics = enrichFromLocalDict(currentLyrics, targetStyle);
                    } else if (AiProviders.isAi(provider)) {
                        List<String> rawLines = new ArrayList<>(currentLyrics.lines().size());
                        for (LyricsLine l : currentLyrics.lines()) {
                            rawLines.add(l.text() != null ? l.text().trim() : "");
                        }

                        List<String> aiLines = LyricsCache.getFuriganaAI(track, currentLyrics.providerName(), rawLines, provider);
                        if (aiLines == null || aiLines.size() != rawLines.size()) {
                            String prompt = OpenAIClient.renderPrompt(
                                    Settings.LYRICS_AI_FURIGANA_PROMPT.get(),
                                    "furigana",
                                    "Japanese",
                                    track != null ? track.title() : "",
                                    track != null ? track.artist() : "",
                                    rawLines);
                            AiProviders.Result ai = AiProviders.mapLines(provider, prompt, rawLines);
                            if (ai != null && ai.lines() != null && ai.lines().size() == rawLines.size()) {
                                aiLines = ai.lines();
                                aiModel = ai.model();
                                fromAi = true;
                                LyricsCache.putFuriganaAI(track, currentLyrics.providerName(), rawLines, aiLines, provider);
                            }
                        } else {
                            fromAi = true;
                            aiModel = Settings.LYRICS_AI_MODEL.get();
                        }

                        if (aiLines != null && aiLines.size() == currentLyrics.lines().size()) {
                            currentLyrics = enrichFromAiLines(currentLyrics, aiLines, targetStyle);
                        }
                    }
                }

                final Lyrics result = currentLyrics;
                final boolean finalFromAi = fromAi;
                final String finalModel = aiModel;
                Utils.runOnMainThread(() -> callback.onGenerated(result, finalFromAi, finalModel));
            } catch (Exception ex) {
                Logger.printInfo(() -> "Furigana generation failed", ex);
                Utils.runOnMainThread(() -> callback.onGenerated(startLyrics, false, null));
            }
        });
    }

    private static boolean hasJapaneseOrRuby(List<LyricsLine> lines) {
        for (LyricsLine l : lines) {
            String text = l.text();
            if (text != null && (ScriptDetector.containsKana(text)
                    || ScriptDetector.contains(text, Character.UnicodeScript.HAN))) {
                return true;
            }
            if (l.words() != null) {
                for (Word w : l.words()) {
                    if (w.ruby() != null && !w.ruby().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static Lyrics formatExistingRuby(Lyrics lyrics, String targetStyle) {
        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> newLines = new ArrayList<>(origLines.size());

        for (LyricsLine line : origLines) {
            if (!line.hasWords()) {
                newLines.add(line);
                continue;
            }

            List<Word> words = line.words();
            List<Word> newWords = new ArrayList<>(words.size());
            boolean changed = false;

            for (Word w : words) {
                if (w.ruby() != null && !w.ruby().isEmpty()) {
                    String formatted = formatRuby(w.ruby(), targetStyle);
                    newWords.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), w.endsWithSpace(), formatted));
                    changed = true;
                } else {
                    newWords.add(w);
                }
            }

            newLines.add(changed
                    ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), newWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                    : line);
        }

        return lyrics.withLines(newLines);
    }

    public static Lyrics enrichFromLocalDict(Lyrics lyrics, String targetStyle) {
        WordReadingTable table = JapaneseDictStore.table();

        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> newLines = new ArrayList<>(origLines.size());

        for (int li = 0; li < origLines.size(); li++) {
            LyricsLine line = origLines.get(li);
            long startMs = line.startTimeMs() >= 0 ? line.startTimeMs() : 0L;
            long endMs = line.endTimeMs() > startMs ? line.endTimeMs()
                    : (li + 1 < origLines.size() && origLines.get(li + 1).startTimeMs() > startMs
                    ? origLines.get(li + 1).startTimeMs()
                    : startMs + 3000L);
            newLines.add(enrichSingleLineLocal(line, table, targetStyle, startMs, endMs));
        }

        return lyrics.withLines(newLines);
    }

    private static LyricsLine enrichSingleLineLocal(LyricsLine line,
                                                    @Nullable WordReadingTable table,
                                                    String targetStyle,
                                                    long fallbackStartMs,
                                                    long fallbackEndMs) {
        String lineText = line.text();
        if (lineText == null || lineText.isEmpty()) {
            return line;
        }
        final boolean hasHan = ScriptDetector.contains(lineText, Character.UnicodeScript.HAN);
        final boolean hasKatakana = ScriptDetector.contains(lineText, Character.UnicodeScript.KATAKANA);
        boolean hasExistingRuby = false;
        if (line.hasWords()) {
            for (Word w : line.words()) {
                if (w.ruby() != null && !w.ruby().isEmpty()) {
                    hasExistingRuby = true;
                    break;
                }
            }
        }
        if (!hasHan && !hasKatakana && !hasExistingRuby) {
            return line;
        }

        // Segment wordless lines or compound tokens so Kanji and Katakana each get their own slot
        List<Word> sourceWords = ensureSegmentedWords(line, table, fallbackStartMs, fallbackEndMs);
        if (sourceWords.isEmpty()) {
            return line;
        }

        List<Word> newWords = new ArrayList<>(sourceWords.size());
        boolean changed = !line.hasWords() || sourceWords.size() != line.words().size();

        int i = 0;
        while (i < sourceWords.size()) {
            Word w = sourceWords.get(i);
            String text = w.text() != null ? w.text().trim() : "";

            if (w.ruby() != null && !w.ruby().isEmpty()) {
                String formatted = formatRuby(w.ruby(), targetStyle);
                String rom = w.romaji();
                if ((rom == null || rom.isEmpty()) && ScriptDetector.containsKana(text)
                        && !ScriptDetector.contains(text, Character.UnicodeScript.HAN)) {
                    rom = KanaRomanizer.romanizeSyllables(text);
                    changed = true;
                }
                if (!formatted.equals(w.ruby())) {
                    changed = true;
                }
                newWords.add(new Word(w.startMs(), w.endMs(), w.text(), rom, w.endsWithSpace(), formatted));
                i++;
                continue;
            }

            // Case B: Pure Hiragana word -> ensure mora-spaced romaji is available for Layer 3
            if (!text.isEmpty() && !ScriptDetector.contains(text, Character.UnicodeScript.HAN)) {
                if (w.romaji() == null && ScriptDetector.containsKana(text)) {
                    String rom = KanaRomanizer.romanizeSyllables(text);
                    newWords.add(new Word(w.startMs(), w.endMs(), w.text(), rom, w.endsWithSpace(), null));
                    changed = true;
                } else {
                    newWords.add(w);
                }
                i++;
                continue;
            }

            if (text.isEmpty()) {
                newWords.add(w);
                i++;
                continue;
            }

            // Case C0: 2-kanji compound stored as a single token (e.g. "以下" -> "以"[い/i] + "下"[か/ka],
            // "以上" -> "以"[い/i] + "上"[じょう/jo u])
            if (text.equals("以下") || text.equals("以上")) {
                String pairReading = lookupExactReading(text, table);
                if (pairReading != null) {
                    String hiraPair = KanaRomanizer.toHiragana(pairReading);
                    String[] splitPair = splitTwoKanjiReading(text, hiraPair, table);
                    if (splitPair != null) {
                        String r1 = formatRuby(splitPair[0], targetStyle);
                        String r2 = formatRuby(splitPair[1], targetStyle);
                        String rom1 = KanaRomanizer.romanizeSyllables(splitPair[0]);
                        String rom2 = KanaRomanizer.romanizeSyllables(splitPair[1]);
                        long ws = w.startMs();
                        long we = Math.max(ws + 2L, w.endMs());
                        long midMs = ws + (we - ws) / 2L;
                        newWords.add(new Word(ws, midMs, text.substring(0, 1), rom1, false, r1));
                        newWords.add(new Word(midMs, we, text.substring(1, 2), rom2, w.endsWithSpace(), r2));
                        changed = true;
                        i++;
                        continue;
                    }
                }
            }

            // Case C: 2-kanji compound across two consecutive single-kanji words (e.g. "以" + "下" -> "い" + "か",
            // "以" + "上" -> "い" + "じょう")
            if (text.length() == 1 && ScriptDetector.isHan(text.charAt(0)) && i + 1 < sourceWords.size()) {
                Word nextW = sourceWords.get(i + 1);
                String nextText = nextW.text() != null ? nextW.text().trim() : "";
                if ((nextW.ruby() == null || nextW.ruby().isEmpty())
                        && nextText.length() == 1 && ScriptDetector.isHan(nextText.charAt(0))) {
                    String pairReading = lookupExactReading(text + nextText, table);
                    if (pairReading != null) {
                        String hiraPair = KanaRomanizer.toHiragana(pairReading);
                        String[] splitPair = splitTwoKanjiReading(text + nextText, hiraPair, table);
                        if (splitPair != null) {
                            String r1 = formatRuby(splitPair[0], targetStyle);
                            String r2 = formatRuby(splitPair[1], targetStyle);
                            String rom1 = (w.romaji() != null && !w.romaji().isEmpty())
                                    ? w.romaji() : KanaRomanizer.romanizeSyllables(splitPair[0]);
                            String rom2 = (nextW.romaji() != null && !nextW.romaji().isEmpty())
                                    ? nextW.romaji() : KanaRomanizer.romanizeSyllables(splitPair[1]);
                            newWords.add(new Word(w.startMs(), w.endMs(), w.text(), rom1, w.endsWithSpace(), r1));
                            newWords.add(new Word(nextW.startMs(), nextW.endMs(), nextW.text(), rom2, nextW.endsWithSpace(), r2));
                            changed = true;
                            i += 2;
                            continue;
                        }
                    }
                }
            }

            // Case D: Kanji word (single or with okurigana)
            String reading = lookupReading(text, table);
            if ((reading == null || reading.isEmpty())
                    && w.romaji() != null && !w.romaji().trim().isEmpty()
                    && ScriptDetector.containsKana(KanaRomanizer.romajiToHiragana(w.romaji()))) {
                reading = KanaRomanizer.romajiToHiragana(w.romaji());
            }

            if (reading != null && !reading.isEmpty()) {
                String stemRuby = stripOkurigana(text, reading);
                String formatted = formatRuby(stemRuby, targetStyle);
                String rom = (w.romaji() != null && !w.romaji().isEmpty())
                        ? w.romaji()
                        : KanaRomanizer.romanizeSyllables(KanaRomanizer.toHiragana(reading));
                newWords.add(new Word(w.startMs(), w.endMs(), w.text(), rom, w.endsWithSpace(), formatted));
                changed = true;
            } else {
                newWords.add(w);
            }
            i++;
        }

        return changed
                ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), newWords,
                line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                : line;
    }


    public static boolean isKatakanaChar(char c) {
        return (c >= 0x30A1 && c <= 0x30FA) || c == 0x30FC || c == 0x30FB;
    }

    public static boolean isHiraganaChar(char c) {
        return c >= 0x3041 && c <= 0x3096;
    }

    private static List<Word> ensureSegmentedWords(LyricsLine line,
                                                   @Nullable WordReadingTable table,
                                                   long fallbackStartMs,
                                                   long fallbackEndMs) {
        final long lineStart = line.startTimeMs() >= 0 ? line.startTimeMs() : Math.max(0L, fallbackStartMs);
        final long lineEnd = line.endTimeMs() > lineStart ? line.endTimeMs() : Math.max(lineStart + 1000L, fallbackEndMs);

        if (!line.hasWords()) {
            return segmentTextIntoWords(line.text(), lineStart, lineEnd, table);
        }

        List<Word> orig = line.words();
        List<Word> expanded = new ArrayList<>(orig.size() + 4);
        for (Word w : orig) {
            String t = w.text();
            if (w.ruby() == null && t != null && needsCompoundSplit(t, table)) {
                long ws = w.startMs() >= 0 ? w.startMs() : lineStart;
                long we = w.endMs() > ws ? w.endMs() : Math.max(ws + 200L, lineEnd);
                List<Word> sub = segmentTextIntoWords(t, ws, we, table);
                if (sub.size() > 1) {
                    Word last = sub.get(sub.size() - 1);
                    if (last.endsWithSpace() != w.endsWithSpace()) {
                        sub.set(sub.size() - 1, new Word(
                                last.startMs(), last.endMs(), last.text(), last.romaji(), w.endsWithSpace(), last.ruby()));
                    }
                    expanded.addAll(sub);
                    continue;
                }
            }
            expanded.add(w);
        }
        return expanded;
    }

    /**
     * Returns true when a token mixes Katakana + Hiragana (e.g. "ノルマは"), mixes Katakana + Kanji
     * (e.g. "ノルマ以下"), or contains multiple Kanji+Kana transitions (e.g. "深く朱き潤む瞳").
     */
    private static boolean needsCompoundSplit(String text, @Nullable WordReadingTable table) {
        String clean = text.trim();
        if (clean.length() <= 1) {
            return false;
        }
        boolean hasKatakana = false;
        boolean hasHiragana = false;
        boolean hasHan = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (isKatakanaChar(c)) hasKatakana = true;
            else if (isHiraganaChar(c)) hasHiragana = true;
            else if (ScriptDetector.isHan(c)) hasHan = true;
        }
        // Any token mixing Katakana with Hiragana or Kanji should be split so the Katakana word is isolated
        if (hasKatakana && (hasHiragana || hasHan)) {
            return true;
        }
        if (!hasHan) {
            return false;
        }
        if (lookupExactReading(clean, table) != null) {
            return false;
        }
        boolean seenKanaAfterHan = false;
        boolean seenHan = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (ScriptDetector.isHan(c)) {
                if (seenKanaAfterHan || (i > 0 && ScriptDetector.isKana(clean.charAt(i - 1)))) {
                    return true;
                }
                seenHan = true;
            } else if (ScriptDetector.isKana(c) && seenHan) {
                seenKanaAfterHan = true;
            }
        }
        return clean.length() >= 4 && seenHan && lookupReading(clean, table) != null
                && !lookupReading(clean, table).equals(lookupExactReading(clean, table));
    }

    /**
     * Segments a Japanese line or phrase into natural Kanji(+okurigana), Katakana, Hiragana,
     * and Latin tokens with interpolated timestamps.
     */
    public static List<Word> segmentTextIntoWords(String text, long startMs, long endMs,
                                                  @Nullable WordReadingTable table) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        List<Boolean> trailingSpaces = new ArrayList<>();

        int n = text.length();
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) break;

            char c = text.charAt(i);
            int tokenEnd;
            if (ScriptDetector.isHan(c)) {
                tokenEnd = matchKanjiTokenEnd(text, i, table);
            } else if (isKatakanaChar(c)) {
                tokenEnd = i + 1;
                while (tokenEnd < n && isKatakanaChar(text.charAt(tokenEnd))) {
                    tokenEnd++;
                }
            } else if (isHiraganaChar(c)) {
                tokenEnd = i + 1;
                while (tokenEnd < n && isHiraganaChar(text.charAt(tokenEnd))) {
                    tokenEnd++;
                }
            } else {
                tokenEnd = i + 1;
                while (tokenEnd < n
                        && !Character.isWhitespace(text.charAt(tokenEnd))
                        && !ScriptDetector.isHan(text.charAt(tokenEnd))
                        && !ScriptDetector.isKana(text.charAt(tokenEnd))) {
                    tokenEnd++;
                }
            }

            // Attach trailing punctuation (other than brackets/spaces) to the token
            while (tokenEnd < n) {
                char pc = text.charAt(tokenEnd);
                if (!Character.isWhitespace(pc) && !Character.isLetterOrDigit(pc)
                        && !ScriptDetector.isHan(pc) && !ScriptDetector.isKana(pc)) {
                    tokenEnd++;
                } else {
                    break;
                }
            }

            boolean hasSpaceAfter = tokenEnd < n && Character.isWhitespace(text.charAt(tokenEnd));
            tokens.add(text.substring(i, tokenEnd));
            trailingSpaces.add(hasSpaceAfter);
            i = tokenEnd;
        }

        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        int totalChars = 0;
        for (String tok : tokens) {
            totalChars += Math.max(1, tok.length());
        }
        final long totalDur = Math.max(tokens.size(), endMs - startMs);
        long cursorMs = startMs;
        int seenChars = 0;

        List<Word> words = new ArrayList<>(tokens.size());
        for (int idx = 0; idx < tokens.size(); idx++) {
            String tok = tokens.get(idx);
            seenChars += Math.max(1, tok.length());
            long wEnd = (idx == tokens.size() - 1)
                    ? Math.max(cursorMs + 1L, endMs)
                    : Math.max(cursorMs + 1L, startMs + (totalDur * seenChars) / totalChars);
            String rom = ScriptDetector.isKana(tok.charAt(0)) && !ScriptDetector.contains(tok, Character.UnicodeScript.HAN)
                    ? KanaRomanizer.romanizeSyllables(tok)
                    : null;
            words.add(new Word(cursorMs, wEnd, tok, rom, trailingSpaces.get(idx), null));
            cursorMs = wEnd;
        }
        return words;
    }

    private static int matchKanjiTokenEnd(String text, int start, @Nullable WordReadingTable table) {
        final int n = text.length();
        int maxLen = Math.min(WordReadingTable.MAX_WORD_LENGTH, n - start);
        for (int len = maxLen; len >= 2; len--) {
            String sub = text.substring(start, start + len);
            if (!ScriptDetector.isHan(sub.charAt(0))) continue;
            boolean invalidJump = false;
            boolean seenKana = false;
            for (int k = 0; k < sub.length(); k++) {
                if (isHiraganaChar(sub.charAt(k))) {
                    seenKana = true;
                } else if (ScriptDetector.isHan(sub.charAt(k)) && seenKana) {
                    invalidJump = true;
                    break;
                } else if (!ScriptDetector.isHan(sub.charAt(k))) {
                    invalidJump = true;
                    break;
                }
            }
            if (invalidJump) continue;
            if (lookupExactReading(sub, table) != null) {
                return start + len;
            }
        }

        int end = start + 1;
        while (end < n && ScriptDetector.isHan(text.charAt(end))) {
            if (lookupExactReading(text.substring(start, end), table) != null) {
                break;
            }
            end++;
        }
        return end;
    }

    public static String stripOkurigana(String wordText, String reading) {
        if (wordText == null || reading == null || wordText.isEmpty() || reading.isEmpty()) {
            return reading;
        }
        String cleanWord = wordText.trim();
        String hiraReading = KanaRomanizer.toHiragana(reading.trim());
        String hiraWord = KanaRomanizer.toHiragana(cleanWord);

        int wEnd = hiraWord.length();
        int rEnd = hiraReading.length();
        while (wEnd > 0 && rEnd > 1) {
            char wc = hiraWord.charAt(wEnd - 1);
            char rc = hiraReading.charAt(rEnd - 1);
            if (ScriptDetector.isKana(wc) && wc == rc) {
                wEnd--;
                rEnd--;
            } else {
                break;
            }
        }
        if (rEnd < hiraReading.length() && rEnd > 0) {
            return hiraReading.substring(0, rEnd);
        }
        return reading;
    }

    @Nullable
    private static String[] splitTwoKanjiReading(String twoKanji, String hiraReading,
                                                 @Nullable WordReadingTable table) {
        if (twoKanji.equals("以下") && hiraReading.equals("いか")) {
            return new String[]{"い", "か"};
        }
        if (twoKanji.equals("以上") && hiraReading.equals("いじょう")) {
            return new String[]{"い", "じょう"};
        }
        String firstKanji = twoKanji.substring(0, 1);
        String firstRead = lookupExactReading(firstKanji, table);
        if (firstRead != null) {
            String hiraFirst = KanaRomanizer.toHiragana(firstRead);
            if (hiraReading.startsWith(hiraFirst) && hiraReading.length() > hiraFirst.length()) {
                return new String[]{hiraFirst, hiraReading.substring(hiraFirst.length())};
            }
        }
        if (hiraReading.length() == 2) {
            return new String[]{hiraReading.substring(0, 1), hiraReading.substring(1, 2)};
        }
        if (hiraReading.length() >= 3) {
            return new String[]{hiraReading.substring(0, 1), hiraReading.substring(1)};
        }
        return null;
    }

    @Nullable
    private static String lookupExactReading(String clean, @Nullable WordReadingTable table) {
        if (clean == null || clean.isEmpty()) return null;
        if (table != null) {
            String r = table.reading(clean);
            if (r != null && !r.isEmpty()) {
                return r;
            }
        }
        return null;
    }

    @Nullable
    public static String lookupKanjiReading(String wordText) {
        return lookupReading(wordText, JapaneseDictStore.table());
    }

    @Nullable
    private static String lookupReading(String wordText, @Nullable WordReadingTable table) {
        if (wordText == null) return null;
        String clean = wordText.trim();
        if (clean.isEmpty()) return null;

        String exact = lookupExactReading(clean, table);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }

        int limit = Math.min(WordReadingTable.MAX_WORD_LENGTH, clean.length());
        for (int candidate = limit; candidate >= 1; candidate--) {
            String prefix = clean.substring(0, candidate);
            String found = lookupExactReading(prefix, table);
            if (found != null && !found.isEmpty()) {
                return found;
            }
        }
        return null;
    }

    public static Lyrics enrichFromAiLines(Lyrics lyrics, List<String> aiLines, String targetStyle) {
        List<LyricsLine> origLines = lyrics.lines();
        List<LyricsLine> newLines = new ArrayList<>(origLines.size());
        WordReadingTable table = JapaneseDictStore.table();

        for (int li = 0; li < origLines.size(); li++) {
            LyricsLine line = origLines.get(li);
            if (li >= aiLines.size()) {
                newLines.add(line);
                continue;
            }

            String aiLine = aiLines.get(li);
            if (aiLine == null || aiLine.trim().isEmpty()) {
                newLines.add(line);
                continue;
            }

            Map<String, String> aiRubyMap = parseAiBrackets(aiLine);
            if (aiRubyMap.isEmpty()) {
                newLines.add(line);
                continue;
            }

            long startMs = line.startTimeMs() >= 0 ? line.startTimeMs() : 0L;
            long endMs = line.endTimeMs() > startMs ? line.endTimeMs()
                    : (li + 1 < origLines.size() && origLines.get(li + 1).startTimeMs() > startMs
                    ? origLines.get(li + 1).startTimeMs()
                    : startMs + 3000L);

            List<Word> words = ensureSegmentedWords(line, table, startMs, endMs);
            if (words.isEmpty()) {
                newLines.add(line);
                continue;
            }

            List<Word> newWords = new ArrayList<>(words.size());
            boolean changed = !line.hasWords() || words.size() != line.words().size();

            for (Word w : words) {
                if (w.ruby() != null && !w.ruby().isEmpty()) {
                    newWords.add(w);
                } else if (w.text() != null && (ScriptDetector.contains(w.text(), Character.UnicodeScript.HAN)
                        || ScriptDetector.containsKana(w.text()))) {
                    String clean = w.text().trim();
                    String reading = aiRubyMap.get(clean);
                    if (reading == null) {
                        for (Map.Entry<String, String> e : aiRubyMap.entrySet()) {
                            if (clean.contains(e.getKey()) || e.getKey().contains(clean)) {
                                reading = e.getValue();
                                break;
                            }
                        }
                    }
                    if (reading != null && !reading.isEmpty()) {
                        String stemRuby = ScriptDetector.contains(clean, Character.UnicodeScript.HAN)
                                ? stripOkurigana(clean, reading) : reading;
                        String formatted = formatRuby(stemRuby, targetStyle);
                        newWords.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), w.endsWithSpace(), formatted));
                        changed = true;
                    } else {
                        newWords.add(w);
                    }
                } else {
                    newWords.add(w);
                }
            }

            newLines.add(changed
                    ? new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(), newWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart())
                    : line);
        }

        return lyrics.withLines(newLines);
    }

    public static Map<String, String> parseAiBrackets(String aiLine) {
        Map<String, String> map = new HashMap<>();
        if (aiLine == null) return map;
        Matcher m = BRACKET_RUBY_PATTERN.matcher(aiLine);
        while (m.find()) {
            String base = m.group(1).trim();
            String ruby = m.group(2).trim();
            if (!base.isEmpty() && !ruby.isEmpty()) {
                map.put(base, ruby);
            }
        }
        return map;
    }

    public static String formatRuby(String ruby, String targetStyle) {
        if (ruby == null || ruby.isEmpty()) return ruby;
        // Preserve pure Latin/English ruby (such as "Normal" above "ノルマ") without altering case/script
        if (!ScriptDetector.containsKana(ruby)) {
            return ruby;
        }
        if ("KATAKANA".equalsIgnoreCase(targetStyle)) {
            return KanaRomanizer.toKatakana(ruby);
        }
        return KanaRomanizer.toHiragana(ruby);
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits Hepburn/Kunrei Japanese romaji words into moraic units and aligns compound readings
 * across per-character Kanji + Okurigana slots without shifting romaji rightward or compressing
 * multi-character sweeps onto a single kanji.
 */
public final class JapaneseMoraSplitter {

    private JapaneseMoraSplitter() {
    }

    public record AlignedChunk(String text, boolean trailingSpace) {
    }

    /**
     * Splits a single Japanese romaji token (e.g. {@code "Toomawari"}, {@code "shite"},
     * {@code "kitte"}, {@code "unmei"}) into Japanese moras / CV syllables while preserving the
     * original casing of each slice.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "Toomawari"} -> {@code ["Too", "ma", "wa", "ri"]} (coalesces long vowel onto CV
     *       when needed, or {@link #splitMorasRaw} keeps {@code ["To", "o", "ma", "wa", "ri"]})</li>
     *   <li>{@code "shite"} -> {@code ["shi", "te"]}</li>
     *   <li>{@code "demo"} -> {@code ["de", "mo"]}</li>
     *   <li>{@code "unmei"} -> {@code ["u", "n", "me", "i"]}</li>
     *   <li>{@code "gakkou"} -> {@code ["ga", "kko", "u"]}</li>
     * </ul>
     */
    public static List<String> splitMorasRaw(String token) {
        if (token == null || token.isEmpty()) {
            return Collections.emptyList();
        }
        final int n = token.length();
        // Verify token contains only Latin letters, macrons, apostrophes, or trailing punctuation.
        int letterCount = 0;
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            if (isRomajiLetter(c)) {
                letterCount++;
            } else if (c != '\'' && c != '-' && isMiddleIndex(token, i)) {
                return List.of(token);
            }
        }
        if (letterCount == 0) {
            return List.of(token);
        }

        List<String> moras = new ArrayList<>(Math.max(2, n / 2));
        int i = 0;
        // Include any leading non-letter marks with the first mora.
        int prefixStart = 0;
        while (i < n && !isRomajiLetter(token.charAt(i))) {
            i++;
        }

        while (i < n) {
            int start = prefixStart >= 0 ? prefixStart : i;
            prefixStart = -1;

            char c0 = lowerAscii(token.charAt(i));
            if (!isRomajiLetter(token.charAt(i))) {
                // Attach trailing punctuation to the last mora.
                if (!moras.isEmpty()) {
                    int last = moras.size() - 1;
                    moras.set(last, moras.get(last) + token.substring(i));
                } else {
                    moras.add(token.substring(start));
                }
                break;
            }

            // 1. Standalone vowel (including macron vowels ā ē ī ō ū).
            if (isVowelOrMacron(token.charAt(i))) {
                i++;
                i = consumeApostrophe(token, i);
                moras.add(token.substring(start, i));
                continue;
            }

            // 2. Syllabic 'n' / 'm' (ん):
            //    - 'n' followed by apostrophe/hyphen ("n'ai")
            //    - 'n' at end of word ("un", "kimi-n")
            //    - 'n' followed by a consonant OTHER than 'y' ("unmei", "kantan", "konnichiwa" -> ko-n-ni-chi-wa)
            //    - 'm' before 'b', 'p', 'm' ("shimbun", "sampo", "unmei")
            if (c0 == 'n') {
                if (i + 1 >= n || !isRomajiLetter(token.charAt(i + 1))) {
                    i++;
                    moras.add(token.substring(start, i));
                    continue;
                }
                char c1 = token.charAt(i + 1);
                if (c1 == '\'' || c1 == '-') {
                    i += 2;
                    moras.add(token.substring(start, i));
                    continue;
                }
                char lc1 = lowerAscii(c1);
                if (!isVowelOrMacron(c1) && lc1 != 'y') {
                    i++;
                    moras.add(token.substring(start, i));
                    continue;
                }
            } else if (c0 == 'm' && i + 1 < n) {
                char lc1 = lowerAscii(token.charAt(i + 1));
                if (lc1 == 'b' || lc1 == 'p' || lc1 == 'm') {
                    i++;
                    moras.add(token.substring(start, i));
                    continue;
                }
            }

            // 3. Geminate sokuon consonant ('kk', 'ss', 'tt', 'pp', 'tch', 'ssh', 'dd', 'gg', 'zz', 'jj'):
            //    Keep the sokuon as its own mora ("kitte" -> ["ki", "t", "te"]) so per-character kana
            //    slots match 1:1 and multi-kanji compounds can coalesce ["ga"+"k" -> "gak", "ko"+"u" -> "kou"].
            if (i + 1 < n && isRomajiLetter(token.charAt(i + 1))) {
                char lc1 = lowerAscii(token.charAt(i + 1));
                if ((c0 == lc1 && !isVowelOrMacron(token.charAt(i)) && c0 != 'n')
                        || (c0 == 't' && lc1 == 'c' && i + 2 < n && lowerAscii(token.charAt(i + 2)) == 'h')) {
                    i++;
                    moras.add(token.substring(start, i));
                    continue;
                }
            }

            // 4. Consonant onset + vowel:
            //    3-char onsets: "tsu", "chi", "shi", "sha", "shu", "sho", "cha", "chu", "cho",
            //                   "kya", "kyu", "kyo", "gya", "gyu", "gyo", "nya", "nyu", "nyo",
            //                   "hya", "hyu", "hyo", "bya", "byu", "byo", "pya", "pyu", "pyo",
            //                   "mya", "myu", "myo", "rya", "ryu", "ryo", "dzu", "jya", "jyu", "jyo"
            int onsetLen = onsetLength(token, i);
            int afterOnset = i + onsetLen;
            if (afterOnset < n && isVowelOrMacron(token.charAt(afterOnset))) {
                i = afterOnset + 1;
                i = consumeApostrophe(token, i);
                moras.add(token.substring(start, i));
            } else {
                // Non-standard cluster (e.g. loanword or English fragment): consume up to next vowel + 1.
                i++;
                while (i < n && isRomajiLetter(token.charAt(i)) && !isVowelOrMacron(token.charAt(i))) {
                    i++;
                }
                if (i < n && isVowelOrMacron(token.charAt(i))) {
                    i++;
                }
                moras.add(token.substring(start, i));
            }
        }

        // Attach any trailing punctuation that remained at the end of token.
        if (i < n && !moras.isEmpty()) {
            int last = moras.size() - 1;
            moras.set(last, moras.get(last) + token.substring(i));
        }
        return moras;
    }

    /**
     * Groups raw moras into syllable-sized units by merging standalone coda/long-vowel pieces
     * ({@code ["To", "o", "ma", "wa", "ri"] -> ["Too", "ma", "wa", "ri"]},
     * {@code ["u", "n", "me", "i"] -> ["un", "mei"]}) when reducing down toward {@code targetCount}.
     */
    public static List<String> partitionMorasToSlots(
            String token,
            int targetSlots,
            @Nullable List<String> slotGuides,
            @Nullable String[] slotTexts) {
        if (targetSlots <= 0) {
            return Collections.emptyList();
        }
        if (targetSlots == 1 || token == null || token.isEmpty()) {
            return List.of(token != null ? token : "");
        }

        // First: strip matching trailing okurigana slots from the right!
        // Example: token="Toomawari", slots=["遠", "回", "り"], slotGuides=["", "", "ri"]
        //   -> slot 2 gets "ri" directly! Remaining token="Toomawa" across 2 kanji ["遠", "回"]!
        // Example: token="mitsuketa", slots=["見", "つ", "け", "た"], slotGuides=["", "tsu", "ke", "ta"]
        //   -> slots 1, 2, 3 get "tsu", "ke", "ta"; slot 0 ("見") gets "mi"!
        String[] result = new String[targetSlots];
        int rightSlot = targetSlots - 1;
        int tokenEnd = token.length();

        // Separate trailing punctuation from token so okurigana suffix matching isn't blocked by '!' or ','
        int punctStart = tokenEnd;
        while (punctStart > 0 && !isRomajiLetter(token.charAt(punctStart - 1))) {
            punctStart--;
        }
        String trailingPunct = token.substring(punctStart);
        String cleanToken = token.substring(0, punctStart);
        int cleanEnd = cleanToken.length();

        while (rightSlot > 0 && cleanEnd > 0) {
            String slotText = slotTexts != null && rightSlot < slotTexts.length ? slotTexts[rightSlot] : "";
            String guide = slotGuides != null && rightSlot < slotGuides.size() ? slotGuides.get(rightSlot) : "";
            boolean isKanaSlot = !slotText.isEmpty() && isAllKanaOrPunct(slotText);
            if (!isKanaSlot || guide == null || guide.isEmpty()) {
                break;
            }
            String normGuide = normalizeLetters(guide);
            if (normGuide.isEmpty()) {
                break;
            }
            int matchStart = endsWithNormalized(cleanToken, cleanEnd, normGuide);
            if (matchStart <= 0) {
                break;
            }
            String piece = cleanToken.substring(matchStart, cleanEnd);
            if (rightSlot == targetSlots - 1 && !trailingPunct.isEmpty()) {
                piece = piece + trailingPunct;
                trailingPunct = "";
            }
            result[rightSlot] = piece;
            cleanEnd = matchStart;
            rightSlot--;
        }

        // Also strip matching leading kana prefix slots from the left (e.g. "お祝い" -> "o" + "iwai").
        int leftSlot = 0;
        int cleanStart = 0;
        while (leftSlot < rightSlot && cleanStart < cleanEnd) {
            String slotText = slotTexts != null && leftSlot < slotTexts.length ? slotTexts[leftSlot] : "";
            String guide = slotGuides != null && leftSlot < slotGuides.size() ? slotGuides.get(leftSlot) : "";
            boolean isKanaSlot = !slotText.isEmpty() && isAllKanaOrPunct(slotText);
            if (!isKanaSlot || guide == null || guide.isEmpty()) {
                break;
            }
            String normGuide = normalizeLetters(guide);
            if (normGuide.isEmpty()) {
                break;
            }
            int matchEnd = startsWithNormalized(cleanToken, cleanStart, cleanEnd, normGuide);
            if (matchEnd < 0 || matchEnd >= cleanEnd) {
                break;
            }
            result[leftSlot] = cleanToken.substring(cleanStart, matchEnd);
            cleanStart = matchEnd;
            leftSlot++;
        }

        String middle = cleanToken.substring(cleanStart, cleanEnd)
                + (rightSlot == targetSlots - 1 ? trailingPunct : "");
        int remSlots = rightSlot - leftSlot + 1;

        if (remSlots == 1) {
            result[leftSlot] = middle;
        } else {
            List<String> moras = splitMorasRaw(middle);
            List<String> grouped = fitMorasToCount(moras, middle, remSlots,
                    slotGuides != null ? slotGuides.subList(
                            Math.min(leftSlot, slotGuides.size()),
                            Math.min(rightSlot + 1, slotGuides.size())) : null);
            for (int k = 0; k < remSlots; k++) {
                result[leftSlot + k] = k < grouped.size() ? grouped.get(k) : "";
            }
        }

        List<String> out = new ArrayList<>(targetSlots);
        for (String s : result) {
            out.add(s != null ? s : "");
        }
        return out;
    }

    private static List<String> fitMorasToCount(
            List<String> rawMoras,
            String original,
            int count,
            @Nullable List<String> guides) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        if (count == 1) {
            return List.of(original);
        }
        if (rawMoras.isEmpty()) {
            List<String> fallback = new ArrayList<>(count);
            fallback.add(original);
            for (int i = 1; i < count; i++) fallback.add("");
            return fallback;
        }
        if (rawMoras.size() == count) {
            return rawMoras;
        }

        // First coalesce dependent moras (geminate consonant onto preceding/following kanji,
        // syllabic 'n'/'m' and long vowels onto preceding CV) until size == count or no more dependent moras.
        List<String> units = new ArrayList<>(rawMoras);
        // Merge standalone geminate consonant (e.g. ["ga", "k", "ko", "u"] -> ["gak", "ko", "u"])
        for (int i = 1; i < units.size() && units.size() > count; i++) {
            String u = units.get(i);
            if (u.length() == 1 && !isVowelOrMacron(u.charAt(0)) && lowerAscii(u.charAt(0)) != 'n') {
                units.set(i - 1, units.get(i - 1) + u);
                units.remove(i);
                i--;
            }
        }
        // Merge syllabic 'n'/'m' onto preceding CV ("u"+"n" -> "un", "ka"+"n" -> "kan")
        for (int i = 1; i < units.size() && units.size() > count; i++) {
            String u = units.get(i);
            if (u.length() == 1 && (lowerAscii(u.charAt(0)) == 'n' || lowerAscii(u.charAt(0)) == 'm')) {
                units.set(i - 1, units.get(i - 1) + u);
                units.remove(i);
                i--;
            }
        }
        // Merge long/diphthong single vowel onto preceding CV ("To"+"o" -> "Too", "me"+"i" -> "mei", "ko"+"u" -> "kou")
        for (int i = 1; i < units.size() && units.size() > count; i++) {
            String u = units.get(i);
            if (u.length() == 1 && isVowelOrMacron(u.charAt(0)) && !units.get(i - 1).isEmpty()) {
                units.set(i - 1, units.get(i - 1) + u);
                units.remove(i);
                i--;
            }
        }

        if (units.size() == count) {
            return units;
        }

        // If guides exist and the first guide matches a prefix of units, anchor it!
        if (guides != null && !guides.isEmpty() && units.size() > count) {
            String g0 = normalizeLetters(guides.get(0));
            if (!g0.isEmpty()) {
                StringBuilder acc = new StringBuilder();
                for (int take = 1; take <= units.size() - (count - 1); take++) {
                    acc.append(normalizeLetters(units.get(take - 1)));
                    if (acc.toString().equals(g0)) {
                        List<String> out = new ArrayList<>(count);
                        StringBuilder first = new StringBuilder();
                        for (int j = 0; j < take; j++) first.append(units.get(j));
                        out.add(first.toString());
                        List<String> tail = fitMorasToCount(
                                units.subList(take, units.size()),
                                joinList(units, take, units.size()),
                                count - 1,
                                guides.size() > 1 ? guides.subList(1, guides.size()) : null);
                        out.addAll(tail);
                        return out;
                    }
                }
            }
        }

        if (units.size() > count) {
            // Distribute units evenly across `count` slots (e.g. 3 moras ["ma", "wa"] across 1 kanji -> "mawa",
            // or ["to", "ma", "do"] across 2 kanji ["戸", "惑"] -> ["to", "mado"] or ["toma", "do"]).
            // In Japanese 2-kanji kunyomi compounds (遠回 = too-mawa, 戸惑 = toma-do / to-mado, 足音 = ashi-oto),
            // even distribution `Math.round(i * M / count)` places moras balanced across each kanji!
            List<String> out = new ArrayList<>(count);
            final int m = units.size();
            int prev = 0;
            for (int s = 0; s < count; s++) {
                int end = (s == count - 1) ? m : Math.max(prev + 1, (int) Math.floor((double) (s + 1) * m / count));
                end = Math.min(end, m - (count - 1 - s));
                out.add(joinList(units, prev, end));
                prev = end;
            }
            return out;
        }

        // Fewer moras than slots (e.g. 1-mora reading "hi" across 2 kanji "日" + silent/iteration, or "kyou" ["kyo","u"] vs 2 slots):
        // Note: if rawMoras had enough moras (e.g. "kyou" -> rawMoras=["kyo", "u"], count=2), use rawMoras!
        if (rawMoras.size() >= count) {
            List<String> out = new ArrayList<>(count);
            final int m = rawMoras.size();
            int prev = 0;
            for (int s = 0; s < count; s++) {
                int end = (s == count - 1) ? m : Math.max(prev + 1, (s + 1) * m / count);
                end = Math.min(end, m - (count - 1 - s));
                out.add(joinList(rawMoras, prev, end));
                prev = end;
            }
            return out;
        }

        // Truly fewer moras than slots (e.g. "ki" for 2 chars): place on the FIRST slot, leave rest empty
        // so romaji starts directly under the first kanji and never shifts right!
        List<String> out = new ArrayList<>(count);
        for (int s = 0; s < count; s++) {
            out.add(s < units.size() ? units.get(s) : "");
        }
        return out;
    }

    /** @return one chunk per slot, or null when the line cannot be aligned. */
    @Nullable
    public static AlignedChunk[] alignJapaneseLineToSlots(
            String wholeRoman,
            String[] ownWords,
            boolean[] isLatinWord,
            @Nullable List<String> guides) {
        if (wholeRoman == null || ownWords == null || ownWords.length == 0) {
            return null;
        }
        if (!hasJapaneseScript(ownWords)) {
            return null;
        }
        int[][] rawTokens = LocalRomanizer.readingTokens(wholeRoman);
        if (rawTokens.length == 0) {
            return null;
        }

        final int slotCount = ownWords.length;
        String[] tokens = new String[rawTokens.length];
        boolean[] tokenHasSpaceAfter = new boolean[rawTokens.length];
        for (int t = 0; t < rawTokens.length; t++) {
            tokens[t] = wholeRoman.substring(rawTokens[t][0], rawTokens[t][1]);
            int endIdx = rawTokens[t][1];
            tokenHasSpaceAfter[t] = (t < rawTokens.length - 1)
                    && endIdx < wholeRoman.length()
                    && Character.isWhitespace(wholeRoman.charAt(endIdx));
        }

        // Identify readable (non-punctuation-only) slots.
        int[] readableSlots = new int[slotCount];
        int readableCount = 0;
        for (int i = 0; i < slotCount; i++) {
            if (ownWords[i] != null && hasLettersOrKanaOrHan(ownWords[i])) {
                readableSlots[readableCount++] = i;
            }
        }
        if (readableCount == 0) {
            return null;
        }

        // Fast case: single token across multiple readable slots.
        if (tokens.length == 1) {
            return distributeTokenGroupToSlots(
                    tokens, tokenHasSpaceAfter, 0, 1,
                    ownWords, isLatinWord, guides, readableSlots, 0, readableCount, slotCount);
        }

        // DP alignment where 1 token CAN span a contiguous range of `1..K` readable slots (`sStart..sEnd`)!
        // Unlike allocateReading (which assigns >=1 token to a single slot and leaves other slots empty),
        // this assigns 1 token (e.g. "Toomawari", "shite", "demo") to a span of slots (e.g. ["遠","回","り"],
        // ["し","て"], ["で","も"]) and then splits that token's moras across those slots!
        final int T = tokens.length;
        final int S = readableCount;

        String[] normTokens = new String[T];
        int[] tokenMoraCounts = new int[T];
        for (int t = 0; t < T; t++) {
            normTokens[t] = normalizeLetters(tokens[t]);
            tokenMoraCounts[t] = Math.max(1, splitMorasRaw(tokens[t]).size());
        }

        String[] slotNormGuides = new String[S];
        boolean[] slotIsKanaOrLatin = new boolean[S];
        int[] slotUnits = new int[S];
        for (int s = 0; s < S; s++) {
            int origIdx = readableSlots[s];
            String w = ownWords[origIdx];
            String g = guides != null && origIdx < guides.size() ? guides.get(origIdx) : "";
            slotIsKanaOrLatin[s] = isLatinWord[origIdx] || isAllKanaOrPunct(w);
            if (isLatinWord[origIdx]) {
                slotNormGuides[s] = normalizeLetters(w);
            } else if (slotIsKanaOrPunct(w)) {
                String cleanGuide = normalizeLetters(g);
                if (!cleanGuide.isEmpty() && isParticleGuideOverride(w, cleanGuide)) {
                    slotNormGuides[s] = cleanGuide;
                } else {
                    String kanaRom = KanaRomanizer.romanize(w);
                    slotNormGuides[s] = normalizeLetters(!kanaRom.isEmpty() ? kanaRom : g);
                }
            } else {
                slotNormGuides[s] = normalizeLetters(g);
            }
            slotUnits[s] = Math.max(1, countSpokenChars(w, isLatinWord[origIdx]));
        }

        // dp[t][s] = minimum cost to align tokens[0..t-1] to readableSlots[0..s-1]
        // Transitions:
        //   (A) 1 token `t-1` spans multiple slots `sPrev .. s-1` (`span >= 1`)
        //   (B) multiple tokens `tPrev .. t-1` (`k >= 2`) fit inside 1 multi-char slot `s-1`
        final double INF = 1e12;
        double[][] dp = new double[T + 1][S + 1];
        int[][] prevT = new int[T + 1][S + 1];
        int[][] prevS = new int[T + 1][S + 1];
        for (int t = 0; t <= T; t++) {
            for (int s = 0; s <= S; s++) {
                dp[t][s] = INF;
                prevT[t][s] = -1;
                prevS[t][s] = -1;
            }
        }
        dp[0][0] = 0.0;

        int[][] levBuf = new int[2][64];
        StringBuilder guideConcat = new StringBuilder(32);

        for (int t = 1; t <= T; t++) {
            String nt = normTokens[t - 1];
            int tMoras = tokenMoraCounts[t - 1];
            for (int s = 1; s <= S; s++) {
                // Case A: token (t-1) covers slots [sPrev .. s-1]
                int maxSpan = Math.min(s, Math.max(6, tMoras + 2));
                int unitSum = 0;
                for (int span = 1; span <= maxSpan; span++) {
                    int sPrev = s - span;
                    if (dp[t - 1][sPrev] >= INF * 0.5) continue;
                    unitSum += slotUnits[s - span];
                    double cost = dp[t - 1][sPrev] + spanMatchCost(
                            nt, tMoras, sPrev, s, slotNormGuides, slotIsKanaOrLatin, unitSum, levBuf, guideConcat);
                    if (cost < dp[t][s]) {
                        dp[t][s] = cost;
                        prevT[t][s] = t - 1;
                        prevS[t][s] = sPrev;
                    }
                }

                // Case B: tokens [tPrev .. t-1] (2..4 tokens) map into single slot (s-1)
                String singleGuide = slotNormGuides[s - 1];
                int maxTokensInOneSlot = Math.min(t, Math.max(4, slotUnits[s - 1] + 2));
                for (int k = 2; k <= maxTokensInOneSlot; k++) {
                    int tPrev = t - k;
                    if (dp[tPrev][s - 1] >= INF * 0.5) continue;
                    guideConcat.setLength(0);
                    for (int idx = tPrev; idx < t; idx++) {
                        guideConcat.append(normTokens[idx]);
                    }
                    double mCost = singleGuide.isEmpty()
                            ? Math.abs(k - slotUnits[s - 1]) * 1.8
                            : editCost(guideConcat.toString(), singleGuide, levBuf);
                    double cost = dp[tPrev][s - 1] + mCost + (k - 1) * 0.6;
                    if (cost < dp[t][s]) {
                        dp[t][s] = cost;
                        prevT[t][s] = tPrev;
                        prevS[t][s] = s - 1;
                    }
                }
            }
        }

        if (dp[T][S] >= INF * 0.5) {
            return null;
        }

        AlignedChunk[] out = new AlignedChunk[slotCount];
        for (int i = 0; i < slotCount; i++) {
            out[i] = new AlignedChunk("", false);
        }

        int curT = T;
        int curS = S;
        while (curT > 0 && curS > 0) {
            int pT = prevT[curT][curS];
            int pS = prevS[curT][curS];
            if (pT < 0 || pS < 0) break;

            AlignedChunk[] sub = distributeTokenGroupToSlots(
                    tokens, tokenHasSpaceAfter, pT, curT,
                    ownWords, isLatinWord, guides, readableSlots, pS, curS, slotCount);
            if (sub != null) {
                for (int rs = pS; rs < curS; rs++) {
                    int origIdx = readableSlots[rs];
                    out[origIdx] = sub[origIdx];
                }
            }
            curT = pT;
            curS = pS;
        }
        return out;
    }

    private static AlignedChunk[] distributeTokenGroupToSlots(
            String[] tokens,
            boolean[] tokenHasSpaceAfter,
            int tStart,
            int tEnd,
            String[] ownWords,
            boolean[] isLatinWord,
            @Nullable List<String> guides,
            int[] readableSlots,
            int sStart,
            int sEnd,
            int totalSlots) {
        AlignedChunk[] out = new AlignedChunk[totalSlots];
        for (int i = 0; i < totalSlots; i++) {
            out[i] = new AlignedChunk("", false);
        }
        int spanSlots = sEnd - sStart;
        int spanTokens = tEnd - tStart;
        if (spanSlots <= 0 || spanTokens <= 0) {
            return out;
        }

        boolean groupEndsWithSpace = tokenHasSpaceAfter[tEnd - 1];

        if (spanSlots == 1) {
            int origIdx = readableSlots[sStart];
            if (isLatinWord[origIdx]) {
                return out;
            }
            StringBuilder joined = new StringBuilder();
            for (int t = tStart; t < tEnd; t++) {
                if (t > tStart) joined.append(' ');
                joined.append(tokens[t]);
            }
            out[origIdx] = new AlignedChunk(joined.toString(), groupEndsWithSpace);
            return out;
        }

        // Single token (e.g. "Toomawari", "shite", "demo") across `spanSlots` slots!
        if (spanTokens == 1) {
            String token = tokens[tStart];
            String[] subSlotTexts = new String[spanSlots];
            List<String> subGuides = new ArrayList<>(spanSlots);
            for (int i = 0; i < spanSlots; i++) {
                int origIdx = readableSlots[sStart + i];
                subSlotTexts[i] = ownWords[origIdx];
                String g = guides != null && origIdx < guides.size() ? guides.get(origIdx) : "";
                if ((g == null || g.isEmpty()) && slotIsKanaOrPunct(ownWords[origIdx])) {
                    g = KanaRomanizer.romanize(ownWords[origIdx]);
                }
                subGuides.add(g != null ? g : "");
            }
            List<String> pieces = partitionMorasToSlots(token, spanSlots, subGuides, subSlotTexts);
            int lastNonEmpty = -1;
            for (int i = 0; i < spanSlots; i++) {
                int origIdx = readableSlots[sStart + i];
                String p = (!isLatinWord[origIdx] && i < pieces.size()) ? pieces.get(i) : "";
                if (!p.isEmpty()) {
                    lastNonEmpty = origIdx;
                }
                out[origIdx] = new AlignedChunk(p, false);
            }
            if (groupEndsWithSpace && lastNonEmpty >= 0) {
                out[lastNonEmpty] = new AlignedChunk(out[lastNonEmpty].text(), true);
            }
            return out;
        }

        // General multi-token to multi-slot fallback (rare when DP breaks into minimal steps).
        StringBuilder joined = new StringBuilder();
        for (int t = tStart; t < tEnd; t++) {
            if (t > tStart) joined.append(' ');
            joined.append(tokens[t]);
        }
        out[readableSlots[sStart]] = new AlignedChunk(joined.toString(), groupEndsWithSpace);
        return out;
    }

    private static double spanMatchCost(
            String normToken,
            int tokenMoras,
            int sStart,
            int sEnd,
            String[] slotNormGuides,
            boolean[] slotIsKanaOrLatin,
            int unitSum,
            int[][] levBuf,
            StringBuilder scratch) {
        int span = sEnd - sStart;
        boolean allGuided = true;
        boolean anyGuided = false;
        scratch.setLength(0);
        for (int s = sStart; s < sEnd; s++) {
            String g = slotNormGuides[s];
            if (g.isEmpty()) {
                allGuided = false;
            } else {
                anyGuided = true;
                scratch.append(g);
            }
        }

        if (allGuided) {
            double edit = editCost(normToken, scratch.toString(), levBuf);
            // Strongly reward exact kana/guide boundary matches (e.g. "shite" == "shi"+"te" -> 0.0!).
            if (edit == 0.0) {
                return 0.0;
            }
            // Heavy penalty if every slot is deterministic kana/Latin and the token doesn't match them!
            boolean allDeterministic = true;
            for (int s = sStart; s < sEnd; s++) {
                if (!slotIsKanaOrLatin[s]) {
                    allDeterministic = false;
                    break;
                }
            }
            return allDeterministic ? edit * 2.2 : edit;
        }

        // Mixed unguided kanji + guided okurigana (e.g. ["遠","回","り"] -> ["", "", "ri"] with "toomawari"):
        double cost = Math.abs(tokenMoras - unitSum) * 1.1 + (span - 1) * 0.15;
        if (anyGuided) {
            // Check suffix okurigana match ("toomawari" ends with "ri" -> bonus!)
            int suffixCheck = sEnd - 1;
            int tokEnd = normToken.length();
            boolean suffixMatched = true;
            while (suffixCheck >= sStart && !slotNormGuides[suffixCheck].isEmpty()) {
                String g = slotNormGuides[suffixCheck];
                if (tokEnd >= g.length() && normToken.regionMatches(tokEnd - g.length(), g, 0, g.length())) {
                    tokEnd -= g.length();
                    suffixCheck--;
                } else {
                    suffixMatched = false;
                    break;
                }
            }
            if (suffixMatched && suffixCheck < sEnd - 1 && tokEnd > 0) {
                // Exact okurigana suffix match with non-empty kanji stem remaining!
                int remKanjiSlots = suffixCheck - sStart + 1;
                int remStemMoras = Math.max(1, splitMorasRaw(normToken.substring(0, tokEnd)).size());
                return Math.abs(remStemMoras - remKanjiSlots) * 0.6;
            }
            if (!suffixMatched) {
                cost += 6.0;
            }
        }
        return cost;
    }

    private static boolean isParticleGuideOverride(String kanaSlot, String normGuide) {
        String trimmed = kanaSlot.trim();
        return ("は".equals(trimmed) && ("wa".equals(normGuide) || "ha".equals(normGuide)))
                || ("へ".equals(trimmed) && ("e".equals(normGuide) || "he".equals(normGuide)))
                || ("を".equals(trimmed) && ("o".equals(normGuide) || "wo".equals(normGuide)));
    }

    private static double editCost(String a, String b, int[][] buffers) {
        if (a.equals(b)) return 0.0;
        if (("o".equals(a) && "wo".equals(b)) || ("wo".equals(a) && "o".equals(b))) {
            return 0.1;
        }
        // Equivalence between Hepburn long vowels ("oo"/"ou"/"uu") for low-cost matching.
        String ca = collapseLongVowels(a);
        String cb = collapseLongVowels(b);
        if (ca.equals(cb)) return 0.25;
        return levenshteinEditCost(a, b, buffers, 1.3, 0.3);
    }

    static double levenshteinEditCost(
            String a, String b, int[][] buffers, double emptyWeight, double lengthDiffWeight) {
        if (a.equals(b)) return 0.0;
        int la = a.length();
        int lb = b.length();
        if (la == 0) return lb * emptyWeight;
        if (lb == 0) return la * emptyWeight;
        if (buffers[0].length <= lb) {
            buffers[0] = new int[lb + 1];
            buffers[1] = new int[lb + 1];
        }
        int[] prev = buffers[0];
        int[] curr = buffers[1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            char c1 = a.charAt(i - 1);
            curr[0] = i;
            for (int j = 1; j <= lb; j++) {
                int sub = (c1 == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + sub);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb] + Math.abs(la - lb) * lengthDiffWeight;
    }

    private static String collapseLongVowels(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0) {
                char p = sb.charAt(sb.length() - 1);
                if ((p == 'o' && (c == 'u' || c == 'o')) || (p == 'u' && c == 'u')
                        || (p == 'e' && (c == 'i' || c == 'e')) || (p == 'a' && c == 'a')
                        || (p == 'i' && c == 'i')) {
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean hasJapaneseScript(String[] words) {
        boolean hasKana = false;
        boolean hasHan = false;
        boolean hasHangul = false;
        for (String w : words) {
            if (w == null) continue;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (ScriptDetector.isKana(c)) hasKana = true;
                else if (ScriptDetector.isHan(c)) hasHan = true;
                else if (ScriptDetector.isHangul(c)) hasHangul = true;
            }
        }
        if (hasHangul && !hasKana) {
            return false;
        }
        return hasKana || (hasHan && (KuromojiDictStore.isReady() || JapaneseDictionaryStore.isReady()));
    }

    private static boolean hasLettersOrKanaOrHan(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || ScriptDetector.isKana((char) cp) || ScriptDetector.isHan((char) cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean slotIsKanaOrPunct(String s) {
        return s != null && !s.isEmpty() && isAllKanaOrPunct(s);
    }

    private static boolean isAllKanaOrPunct(String s) {
        boolean anyKana = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (ScriptDetector.isKana(c)) {
                anyKana = true;
            } else if (Character.isLetterOrDigit(c) || ScriptDetector.isHan(c)) {
                return false;
            }
        }
        return anyKana;
    }

    private static int countSpokenChars(String w, boolean isLatin) {
        if (isLatin || w == null || w.isEmpty()) return 1;
        int count = 0;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            // Small palatalizing kana (ゃゅょァィゥェォ) ride on the preceding kana's mora!
            if (isSmallCombiningKana(c)) continue;
            if (ScriptDetector.isHan(c)) {
                count += 2; // Average kanji is ~1.8 moras in Japanese!
            } else if (ScriptDetector.isKana(c)) {
                count += 1;
            }
        }
        return Math.max(1, count);
    }

    static boolean isSmallCombiningKana(char c) {
        return "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ".indexOf(c) >= 0;
    }

    private static int endsWithNormalized(String text, int endExclusive, String normSuffix) {
        int sIdx = normSuffix.length() - 1;
        int tIdx = endExclusive - 1;
        while (tIdx >= 0 && sIdx >= 0) {
            char tc = text.charAt(tIdx);
            if (tc == '\'' || tc == '-') {
                tIdx--;
                continue;
            }
            if (lowerBaseRomaji(tc) != normSuffix.charAt(sIdx)) {
                return -1;
            }
            tIdx--;
            sIdx--;
        }
        return sIdx < 0 ? tIdx + 1 : -1;
    }

    private static int startsWithNormalized(String text, int startInclusive, int endExclusive, String normPrefix) {
        int pIdx = 0;
        int tIdx = startInclusive;
        while (tIdx < endExclusive && pIdx < normPrefix.length()) {
            char tc = text.charAt(tIdx);
            if (tc == '\'' || tc == '-') {
                tIdx++;
                continue;
            }
            if (lowerBaseRomaji(tc) != normPrefix.charAt(pIdx)) {
                return -1;
            }
            tIdx++;
            pIdx++;
        }
        return pIdx == normPrefix.length() ? tIdx : -1;
    }

    static String normalizeLetters(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isRomajiLetter(c)) {
                char b = lowerBaseRomaji(c);
                sb.append(b);
                if (isMacronVowel(c)) {
                    // Expand macron (ō -> oo / ou -> oo) so length matches Hepburn vowels.
                    sb.append(b);
                }
            }
        }
        return sb.toString();
    }

    private static String joinList(List<String> items, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static int onsetLength(String s, int i) {
        final int rem = s.length() - i;
        char c0 = lowerAscii(s.charAt(i));
        if (rem >= 2) {
            char c1 = lowerAscii(s.charAt(i + 1));
            // 2-consonant onsets: sh, ch, ts, ky, gy, ny, hy, by, py, my, ry, jy, dy, ty, sy, zy, dz, kw, gw
            if ((c0 == 's' && c1 == 'h')
                    || (c0 == 'c' && c1 == 'h')
                    || (c0 == 't' && c1 == 's')
                    || (c0 == 'd' && c1 == 'z')
                    || (c1 == 'y' && "kgnhbpmrjdtsz".indexOf(c0) >= 0)
                    || (c1 == 'w' && (c0 == 'k' || c0 == 'g'))) {
                return 2;
            }
        }
        return 1;
    }

    private static int consumeApostrophe(String s, int i) {
        if (i < s.length() && s.charAt(i) == '\'') {
            return i + 1;
        }
        return i;
    }

    private static boolean isMiddleIndex(String s, int idx) {
        for (int k = idx + 1; k < s.length(); k++) {
            if (isRomajiLetter(s.charAt(k))) return true;
        }
        return false;
    }

    private static boolean isRomajiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isMacronVowel(c);
    }

    private static boolean isVowelOrMacron(char c) {
        char lc = lowerAscii(c);
        return lc == 'a' || lc == 'e' || lc == 'i' || lc == 'o' || lc == 'u' || isMacronVowel(c);
    }

    private static boolean isMacronVowel(char c) {
        return switch (c) {
            case 'ā', 'Ā', 'ē', 'Ē', 'ī', 'Ī', 'ō', 'Ō', 'ū', 'Ū', 'â', 'ê', 'î', 'ô', 'û' -> true;
            default -> false;
        };
    }

    private static char lowerBaseRomaji(char c) {
        return switch (c) {
            case 'ā', 'Ā', 'â' -> 'a';
            case 'ē', 'Ē', 'ê' -> 'e';
            case 'ī', 'Ī', 'î' -> 'i';
            case 'ō', 'Ō', 'ô' -> 'o';
            case 'ū', 'Ū', 'û' -> 'u';
            default -> lowerAscii(c);
        };
    }

    private static char lowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
    }
}

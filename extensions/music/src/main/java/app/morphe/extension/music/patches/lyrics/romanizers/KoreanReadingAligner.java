/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseMoraSplitter.AlignedChunk;

/**
 * Aligns a whole-line Korean romanization across timed Hangul syllable/subword slots without
 * dumping whole words onto the first Hangul syllable or letting Latin words drift onto Korean slots.
 */
public final class KoreanReadingAligner {

    private KoreanReadingAligner() {
    }

    /** Returns true when {@code ownWords} contains Hangul script and no Japanese kana. */
    public static boolean hasKoreanScript(@Nullable String[] ownWords) {
        if (ownWords == null || ownWords.length == 0) {
            return false;
        }
        boolean hasHangul = false;
        for (String w : ownWords) {
            if (w == null) continue;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (ScriptDetector.isHangul(c)) {
                    hasHangul = true;
                } else if (ScriptDetector.isKana(c)) {
                    return false;
                }
            }
        }
        return hasHangul;
    }

    /**
     * Aligns {@code wholeRoman} across {@code ownWords} when the line mixes Hangul syllable/subword
     * slots and optional Latin words. Multi-syllable Korean tokens (such as {@code "eonjena"} or
     * {@code "jayeonseureopge"}) are split at Romanized Korean syllable boundaries to match each slot's
     * Hangul syllable count and phonetic guide.
     */
    @Nullable
    public static AlignedChunk[] alignKoreanLineToSlots(
            @Nullable String wholeRoman,
            @Nullable String[] ownWords,
            boolean[] isLatinWord,
            @Nullable List<String> guides) {
        if (wholeRoman == null || ownWords == null || ownWords.length == 0
                || !hasKoreanScript(ownWords)) {
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

        int[] readableSlots = new int[slotCount];
        int readableCount = 0;
        for (int i = 0; i < slotCount; i++) {
            if (ownWords[i] != null && hasReadableChar(ownWords[i])) {
                readableSlots[readableCount++] = i;
            }
        }
        if (readableCount == 0) {
            return null;
        }

        final int T = tokens.length;
        final int S = readableCount;
        String[] normTokens = new String[T];
        int[] tokenSylEst = new int[T];
        for (int t = 0; t < T; t++) {
            normTokens[t] = normalizeForKoreanMatch(tokens[t]);
            tokenSylEst[t] = Math.max(1, segmentKoreanRomanSyllables(tokens[t]).size());
        }

        String[] slotNormGuides = new String[S];
        boolean[] slotIsLatin = new boolean[S];
        int[] slotUnits = new int[S];
        for (int s = 0; s < S; s++) {
            int origIdx = readableSlots[s];
            String w = ownWords[origIdx];
            String g = guides != null && origIdx < guides.size() ? guides.get(origIdx) : "";
            slotIsLatin[s] = isLatinWord[origIdx];
            if (slotIsLatin[s]) {
                slotNormGuides[s] = normalizeAsciiLower(w);
                slotUnits[s] = 1;
            } else {
                String fallbackG = (g == null || g.isEmpty()) ? HangulRomanizer.romanize(w) : g;
                slotNormGuides[s] = normalizeForKoreanMatch(fallbackG);
                slotUnits[s] = Math.max(1, countHangulChars(w));
            }
        }

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
            String rawNormT = normalizeAsciiLower(tokens[t - 1]);
            int tSyls = tokenSylEst[t - 1];
            for (int s = 1; s <= S; s++) {
                // Option A: token (t-1) covers contiguous Hangul slots [sPrev .. s-1], OR 1 Latin slot.
                int maxSpan = slotIsLatin[s - 1] ? 1 : Math.min(s, Math.max(6, tSyls + 2));
                int unitSum = 0;
                for (int span = 1; span <= maxSpan; span++) {
                    int sPrev = s - span;
                    if (dp[t - 1][sPrev] >= INF * 0.5) continue;
                    if (span > 1 && hasAnyLatinInRange(slotIsLatin, sPrev, s)) {
                        break;
                    }
                    unitSum += slotUnits[sPrev];
                    double cost;
                    if (slotIsLatin[s - 1]) {
                        // Latin slots should only consume tokens matching themselves, never Korean words.
                        String latGuide = slotNormGuides[s - 1];
                        double d = editCost(rawNormT, latGuide, levBuf);
                        cost = dp[t - 1][sPrev] + (rawNormT.equals(latGuide) ? 0.0 : (d * 2.5 + 4.0));
                    } else {
                        // Guard: if `rawNormT` identically matches a downstream Latin slot and NOT this Hangul
                        // guide, heavily penalize consuming it onto a Korean slot.
                        guideConcat.setLength(0);
                        for (int idx = sPrev; idx < s; idx++) {
                            guideConcat.append(slotNormGuides[idx]);
                        }
                        double edit = editCost(nt, guideConcat.toString(), levBuf);
                        double lenPenalty = Math.abs(tSyls - unitSum) * 0.9 + (span - 1) * 0.1;
                        double stealPenalty = (edit > 1.5 && matchesLatinSlotAhead(rawNormT, slotIsLatin, slotNormGuides, s))
                                ? 12.0 : 0.0;
                        cost = dp[t - 1][sPrev] + edit + lenPenalty + stealPenalty;
                    }
                    if (cost < dp[t][s]) {
                        dp[t][s] = cost;
                        prevT[t][s] = t - 1;
                        prevS[t][s] = sPrev;
                    }
                }

                // Option B: 2..3 tokens map onto a single multi-syllable Korean/Latin slot (s-1).
                if (!slotIsLatin[s - 1]) {
                    String singleGuide = slotNormGuides[s - 1];
                    int maxTokensInOne = Math.min(t, Math.max(3, slotUnits[s - 1] + 1));
                    for (int k = 2; k <= maxTokensInOne; k++) {
                        int tPrev = t - k;
                        if (dp[tPrev][s - 1] >= INF * 0.5) continue;
                        guideConcat.setLength(0);
                        for (int idx = tPrev; idx < t; idx++) {
                            guideConcat.append(normTokens[idx]);
                        }
                        double mCost = editCost(guideConcat.toString(), singleGuide, levBuf);
                        double cost = dp[tPrev][s - 1] + mCost + (k - 1) * 0.5;
                        if (cost < dp[t][s]) {
                            dp[t][s] = cost;
                            prevT[t][s] = tPrev;
                            prevS[t][s] = s - 1;
                        }
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

            distributeGroupToSlots(
                    tokens, tokenHasSpaceAfter, pT, curT,
                    ownWords, isLatinWord, guides, readableSlots, pS, curS, out);
            curT = pT;
            curS = pS;
        }
        return out;
    }

    private static void distributeGroupToSlots(
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
            AlignedChunk[] out) {
        int spanSlots = sEnd - sStart;
        int spanTokens = tEnd - tStart;
        if (spanSlots <= 0 || spanTokens <= 0) {
            return;
        }
        boolean groupEndsWithSpace = tokenHasSpaceAfter[tEnd - 1];
        if (spanSlots == 1) {
            int origIdx = readableSlots[sStart];
            if (isLatinWord[origIdx]) {
                return;
            }
            StringBuilder joined = new StringBuilder();
            for (int t = tStart; t < tEnd; t++) {
                if (t > tStart) joined.append(' ');
                joined.append(tokens[t]);
            }
            out[origIdx] = new AlignedChunk(joined.toString(), groupEndsWithSpace);
            return;
        }

        if (spanTokens == 1) {
            String token = tokens[tStart];
            int[] weights = new int[spanSlots];
            String[] subGuides = new String[spanSlots];
            for (int i = 0; i < spanSlots; i++) {
                int origIdx = readableSlots[sStart + i];
                weights[i] = Math.max(1, countHangulChars(ownWords[origIdx]));
                String g = guides != null && origIdx < guides.size() ? guides.get(origIdx) : "";
                if (g == null || g.isEmpty()) {
                    g = HangulRomanizer.romanize(ownWords[origIdx]);
                }
                subGuides[i] = g;
            }
            List<String> pieces = splitTokenAcrossSlots(token, spanSlots, weights, subGuides);
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
            return;
        }

        StringBuilder joined = new StringBuilder();
        for (int t = tStart; t < tEnd; t++) {
            if (t > tStart) joined.append(' ');
            joined.append(tokens[t]);
        }
        out[readableSlots[sStart]] = new AlignedChunk(joined.toString(), groupEndsWithSpace);
    }

    /**
     * Partitions one Korean romanized token (e.g. {@code "jayeonseureopge"} or {@code "eonjena"})
     * into {@code slotCount} slot pieces respecting Romanized Korean syllable boundaries and slot guides.
     */
    static List<String> splitTokenAcrossSlots(
            String token, int slotCount, int[] slotSyllableCounts, String[] slotGuides) {
        List<String> syllables = segmentKoreanRomanSyllables(token);
        final int sylLen = syllables.size();
        int totalTargetSyls = 0;
        for (int w : slotSyllableCounts) {
            totalTargetSyls += Math.max(1, w);
        }

        if (sylLen <= 1) {
            List<String> single = new ArrayList<>(slotCount);
            single.add(token);
            for (int i = 1; i < slotCount; i++) single.add("");
            return single;
        }

        // Fast exact match when segmented syllables equal total Hangul chars across slots.
        if (sylLen == totalTargetSyls) {
            List<String> out = new ArrayList<>(slotCount);
            int pos = 0;
            for (int i = 0; i < slotCount; i++) {
                int take = Math.max(1, slotSyllableCounts[i]);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < take && pos < sylLen; k++) {
                    sb.append(syllables.get(pos++));
                }
                out.add(sb.toString());
            }
            return out;
        }

        // DP partitioning `syllables[0..sylLen-1]` over `slotCount` slots matching `slotGuides`!
        final double INF = 1e9;
        double[][] dp = new double[slotCount + 1][sylLen + 1];
        int[][] path = new int[slotCount + 1][sylLen + 1];
        for (int i = 0; i <= slotCount; i++) {
            for (int j = 0; j <= sylLen; j++) {
                dp[i][j] = INF;
                path[i][j] = -1;
            }
        }
        dp[0][0] = 0.0;
        int[][] levBuf = new int[2][48];

        for (int i = 1; i <= slotCount; i++) {
            String guideNorm = normalizeForKoreanMatch(slotGuides[i - 1]);
            int expectedWeight = Math.max(1, slotSyllableCounts[i - 1]);
            for (int j = i; j <= sylLen; j++) {
                for (int prev = i - 1; prev < j; prev++) {
                    if (dp[i - 1][prev] >= INF * 0.5) continue;
                    StringBuilder cand = new StringBuilder();
                    for (int k = prev; k < j; k++) {
                        cand.append(syllables.get(k));
                    }
                    String candNorm = normalizeForKoreanMatch(cand.toString());
                    double cost = dp[i - 1][prev]
                            + editCost(candNorm, guideNorm, levBuf)
                            + Math.abs((j - prev) - expectedWeight) * 0.4;
                    if (cost < dp[i][j]) {
                        dp[i][j] = cost;
                        path[i][j] = prev;
                    }
                }
            }
        }

        if (dp[slotCount][sylLen] >= INF * 0.5) {
            // Proportional slice by Hangul syllable weight if DP cannot guarantee >=1 syl/slot.
            List<String> out = new ArrayList<>(slotCount);
            int cur = 0;
            int remWeight = totalTargetSyls;
            for (int i = 0; i < slotCount; i++) {
                int remSlots = slotCount - i - 1;
                int take = (i == slotCount - 1) ? (sylLen - cur)
                        : Math.max(0, Math.min(sylLen - cur - remSlots,
                        Math.round((float) (sylLen - cur) * slotSyllableCounts[i] / Math.max(1, remWeight))));
                remWeight -= Math.max(1, slotSyllableCounts[i]);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < take && cur < sylLen; k++) {
                    sb.append(syllables.get(cur++));
                }
                out.add(sb.toString());
            }
            return out;
        }

        String[] reversed = new String[slotCount];
        int cur = sylLen;
        for (int i = slotCount; i > 0; i--) {
            int p = path[i][cur];
            StringBuilder sb = new StringBuilder();
            for (int k = p; k < cur; k++) {
                sb.append(syllables.get(k));
            }
            reversed[i - 1] = sb.toString();
            cur = p;
        }
        List<String> out = new ArrayList<>(slotCount);
        for (String s : reversed) out.add(s);
        return out;
    }

    /**
     * Segments a Revised-Romanization Korean token (e.g. {@code "jayeonseureopge"},
     * {@code "al-a,"}, {@code "eonjena"}) into phonetic Korean syllable chunks.
     */
    static List<String> segmentKoreanRomanSyllables(@Nullable String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        final int n = token.length();
        int i = 0;

        // Gather leading punctuation if any.
        int start = 0;
        while (i < n && !isAsciiLetter(token.charAt(i))) {
            i++;
        }

        while (i < n) {
            if (!isAsciiLetter(token.charAt(i))) {
                break;
            }
            int sylStart = (out.isEmpty() && start < i) ? start : i;

            // Optional onset consonant cluster (e.g. "kk", "tt", "pp", "ss", "jj", "ch", "y", "w" or single C).
            if (isConsonant(token.charAt(i))) {
                i += consumeOnset(token, i);
            }

            // Vowel nucleus (Korean diphthongs: eo, eu, ae, yae, yeo, oe, wa, wae, wo, we, wi, ui, etc.).
            if (i < n && isVowel(token.charAt(i))) {
                i += consumeVowelNucleus(token, i);
            } else if (i == sylStart && i < n) {
                i++;
            }

            // Optional coda consonant(s) before either end-of-word, hyphen, or next consonant onset.
            if (i < n && (token.charAt(i) == '-' || token.charAt(i) == '\'')) {
                i++;
            } else if (i < n && isConsonant(token.charAt(i))) {
                int codaLen = consumeCodaBeforeNextSyllable(token, i);
                i += codaLen;
                if (i < n && (token.charAt(i) == '-' || token.charAt(i) == '\'')) {
                    i++;
                }
            }

            out.add(token.substring(sylStart, i));
        }

        if (i < n) {
            if (out.isEmpty()) {
                out.add(token);
            } else {
                int last = out.size() - 1;
                out.set(last, out.get(last) + token.substring(i));
            }
        }
        return out;
    }

    private static int consumeOnset(String s, int i) {
        if (i + 1 < s.length()) {
            char a = lower(s.charAt(i));
            char b = lower(s.charAt(i + 1));
            if ((a == 'k' && b == 'k') || (a == 't' && b == 't') || (a == 'p' && b == 'p')
                    || (a == 's' && b == 's') || (a == 'j' && b == 'j') || (a == 'c' && b == 'h')) {
                return 2;
            }
        }
        return 1;
    }

    private static int consumeVowelNucleus(String s, int i) {
        final int n = s.length();
        char a = lower(s.charAt(i));
        if (i + 2 < n) {
            char b = lower(s.charAt(i + 1));
            char c = lower(s.charAt(i + 2));
            if ((a == 'y' && b == 'a' && c == 'e')
                    || (a == 'y' && b == 'e' && c == 'o')
                    || (a == 'w' && b == 'a' && c == 'e')) {
                return 3;
            }
        }
        if (i + 1 < n) {
            char b = lower(s.charAt(i + 1));
            if ((a == 'e' && (b == 'o' || b == 'u'))
                    || (a == 'a' && b == 'e')
                    || (a == 'o' && b == 'e')
                    || (a == 'u' && b == 'i')
                    || (a == 'y' && (b == 'a' || b == 'e' || b == 'o' || b == 'u'))
                    || (a == 'w' && (b == 'a' || b == 'e' || b == 'o' || b == 'i'))) {
                return 2;
            }
        }
        return 1;
    }

    private static int consumeCodaBeforeNextSyllable(String s, int i) {
        final int n = s.length();
        // Look ahead past consonant(s): if followed by '-' / non-letter / end, all belong to this coda!
        int j = i;
        while (j < n && isConsonant(s.charAt(j))) {
            j++;
        }
        if (j >= n || !isAsciiLetter(s.charAt(j))) {
            // Trailing consonants at end of token (e.g. "-eop", "-al", "-eon", "-eok").
            return j - i;
        }
        // `s.charAt(j)` is a vowel starting the next syllable: keep everything EXCEPT the next onset!
        int runLen = j - i;
        if (runLen == 1) {
            // Single consonant between vowels belongs to the NEXT syllable (e.g. "bo-ji", "a-ra")!
            return 0;
        }
        char a = lower(s.charAt(i));
        char b = lower(s.charAt(i + 1));
        if (runLen == 2) {
            if ((a == 'c' && b == 'h') || (a == b && (a == 'k' || a == 't' || a == 'p' || a == 's' || a == 'j'))) {
                return 0; // Double/ch onset for the next syllable!
            }
            return 1; // 1 coda consonant + 1 next onset consonant (e.g. "eon-je", "seop-ge", "sin-ga").
        }
        // 3+ consonants (e.g. "ngg", "nch", "lgy"): check if first two form "ng" coda!
        if (a == 'n' && b == 'g') {
            return Math.min(2, runLen - 1);
        }
        if (i + 2 < n && lower(s.charAt(j - 2)) == 'c' && lower(s.charAt(j - 1)) == 'h') {
            return runLen - 2;
        }
        return runLen - 1;
    }

    private static boolean hasAnyLatinInRange(boolean[] slotIsLatin, int start, int end) {
        for (int i = start; i < end; i++) {
            if (slotIsLatin[i]) return true;
        }
        return false;
    }

    private static boolean matchesLatinSlotAhead(
            String rawNormT, boolean[] slotIsLatin, String[] slotNormGuides, int fromS) {
        if (rawNormT.isEmpty()) return false;
        for (int i = fromS; i < slotIsLatin.length; i++) {
            if (slotIsLatin[i] && rawNormT.equals(slotNormGuides[i])) {
                return true;
            }
        }
        return false;
    }

    static String normalizeForKoreanMatch(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAsciiLetter(c) || (c >= '0' && c <= '9')) {
                char lc = lower(c);
                // Unify r/l so Google ("geurae", "ara") matches HangulRomanizer ("geulae", "al-a").
                if (lc == 'r') lc = 'l';
                sb.append(lc);
            }
        }
        return sb.toString();
    }

    private static String normalizeAsciiLower(@Nullable String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAsciiLetter(c) || (c >= '0' && c <= '9')) {
                sb.append(lower(c));
            }
        }
        return sb.toString();
    }

    private static double editCost(String a, String b, int[][] buffers) {
        return JapaneseMoraSplitter.levenshteinEditCost(a, b, buffers, 1.2, 0.25);
    }

    private static int countHangulChars(@Nullable String s) {
        if (s == null) return 0;
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (ScriptDetector.isHangul(s.charAt(i))) c++;
        }
        return c;
    }

    private static boolean hasReadableChar(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isVowel(char c) {
        char lc = lower(c);
        return lc == 'a' || lc == 'e' || lc == 'i' || lc == 'o' || lc == 'u' || lc == 'y' || lc == 'w';
    }

    private static boolean isConsonant(char c) {
        return isAsciiLetter(c) && !isVowel(c);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char lower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
    }
}

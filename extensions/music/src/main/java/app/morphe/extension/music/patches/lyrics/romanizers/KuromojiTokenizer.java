/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java Viterbi morphological tokenizer matching {@code kuromoji.js} (IPADIC).
 *
 * <p>Performs code-point-aware Double-Array Trie prefix lookup, unknown character category
 * grouping, and minimum-cost connection-matrix Viterbi search in a single forward pass.
 */
public final class KuromojiTokenizer {

    public record Token(
            String surfaceForm,
            String pos,
            String posDetail1,
            String basicForm,
            String reading,
            String pronunciation,
            boolean known) {
    }

    private static final int TYPE_BOS = 0;
    private static final int TYPE_KNOWN = 1;
    private static final int TYPE_UNKNOWN = 2;

    private static final class Node {
        final int tokenInfoId;
        final short rightId;
        final int type;
        final int startCp;
        final int cpLength;
        final int shortestCost;
        final Node prev;

        Node(int tokenInfoId, short rightId, int type, int startCp, int cpLength,
             int shortestCost, Node prev) {
            this.tokenInfoId = tokenInfoId;
            this.rightId = rightId;
            this.type = type;
            this.startCp = startCp;
            this.cpLength = cpLength;
            this.shortestCost = shortestCost;
            this.prev = prev;
        }
    }

    private final KuromojiDictionary dict;

    public KuromojiTokenizer(KuromojiDictionary dict) {
        this.dict = dict;
    }

    public KuromojiDictionary dictionary() {
        return dict;
    }

    /** Tokenizes {@code text} into IPADIC morphemes, splitting on Japanese clause punctuation. */
    public List<Token> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sentences = splitByPunctuation(text);
        if (sentences.size() == 1) {
            return tokenizeSentence(sentences.get(0));
        }
        List<Token> all = new ArrayList<>();
        for (String sentence : sentences) {
            all.addAll(tokenizeSentence(sentence));
        }
        return all;
    }

    static List<String> splitByPunctuation(String input) {
        List<String> sentences = new ArrayList<>(4);
        int start = 0;
        final int n = input.length();
        for (int i = 0; i < n; i++) {
            char c = input.charAt(i);
            if (c == '、' || c == '。') {
                sentences.add(input.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < n) {
            sentences.add(input.substring(start));
        }
        return sentences;
    }

    private List<Token> tokenizeSentence(String sentence) {
        if (sentence.isEmpty()) {
            return Collections.emptyList();
        }
        int[] cps = sentence.codePoints().toArray();
        final int n = cps.length;
        if (n == 0) {
            return Collections.emptyList();
        }

        // UTF-16 offsets for fast zero-copy substring extraction at the end.
        int[] charOffsets = new int[n + 1];
        int utf16Cursor = 0;
        for (int i = 0; i < n; i++) {
            charOffsets[i] = utf16Cursor;
            utf16Cursor += Character.charCount(cps[i]);
        }
        charOffsets[n] = utf16Cursor;

        @SuppressWarnings("unchecked")
        List<Node>[] nodesEndingAt = new List[n + 1];
        List<Node> bosList = new ArrayList<>(1);
        bosList.add(new Node(-1, (short) 0, TYPE_BOS, 0, 0, 0, null));
        nodesEndingAt[0] = bosList;

        for (int pos = 0; pos < n; pos++) {
            List<Node> prevNodes = nodesEndingAt[pos];
            if (prevNodes == null || prevNodes.isEmpty()) {
                continue;
            }

            List<KuromojiDictionary.PrefixMatch> matches = dict.commonPrefixSearch(cps, pos);
            for (int m = 0; m < matches.size(); m++) {
                KuromojiDictionary.PrefixMatch match = matches.get(m);
                int[] tokenInfoIds = dict.getTargetIds(match.trieId());
                int endPos = pos + match.codePointLength();
                for (int tokenInfoId : tokenInfoIds) {
                    short leftId = dict.knownLeftId(tokenInfoId);
                    short rightId = dict.knownRightId(tokenInfoId);
                    short wordCost = dict.knownWordCost(tokenInfoId);
                    appendRelaxedNode(nodesEndingAt, prevNodes, endPos,
                            tokenInfoId, leftId, rightId, wordCost,
                            TYPE_KNOWN, pos, match.codePointLength());
                }
            }

            KuromojiDictionary.CharacterClass headClass = dict.lookupCharClass(cps[pos]);
            if (matches.isEmpty() || headClass.isAlwaysInvoke()) {
                int unkLen = 1;
                if (headClass.isGrouping() && pos + 1 < n) {
                    int maxLen = headClass.maxLength() > 0 ? headClass.maxLength() : (n - pos);
                    while (unkLen < maxLen && pos + unkLen < n) {
                        KuromojiDictionary.CharacterClass nextClass =
                                dict.lookupCharClass(cps[pos + unkLen]);
                        if (!headClass.className().equals(nextClass.className())) {
                            break;
                        }
                        unkLen++;
                    }
                }
                int[] unkIds = dict.getUnkTargetIds(headClass.classId());
                int endPos = pos + unkLen;
                if (unkIds.length > 0) {
                    for (int unkId : unkIds) {
                        short leftId = dict.unkLeftId(unkId);
                        short rightId = dict.unkRightId(unkId);
                        short wordCost = dict.unkWordCost(unkId);
                        appendRelaxedNode(nodesEndingAt, prevNodes, endPos,
                                unkId, leftId, rightId, wordCost,
                                TYPE_UNKNOWN, pos, unkLen);
                    }
                } else {
                    // Safety fallback so an unmapped unknown class never disconnects the lattice.
                    appendRelaxedNode(nodesEndingAt, prevNodes, endPos,
                            -1, (short) 0, (short) 0, (short) 10000,
                            TYPE_UNKNOWN, pos, unkLen);
                }
            }
        }

        List<Node> finalPrev = nodesEndingAt[n];
        if (finalPrev == null || finalPrev.isEmpty()) {
            return List.of(new Token(sentence, "記号", "一般", sentence, "", "", false));
        }

        // Relax EOS (leftId = 0, wordCost = 0).
        int bestEosCost = Integer.MAX_VALUE;
        Node bestLast = null;
        for (int i = 0; i < finalPrev.size(); i++) {
            Node prev = finalPrev.get(i);
            if (prev.shortestCost == Integer.MAX_VALUE) continue;
            int cost = prev.shortestCost + dict.connectionCost(prev.rightId, 0);
            if (cost < bestEosCost) {
                bestEosCost = cost;
                bestLast = prev;
            }
        }
        if (bestLast == null) {
            return List.of(new Token(sentence, "記号", "一般", sentence, "", "", false));
        }

        List<Node> reversed = new ArrayList<>();
        Node cursor = bestLast;
        while (cursor != null && cursor.type != TYPE_BOS) {
            reversed.add(cursor);
            cursor = cursor.prev;
        }
        Collections.reverse(reversed);

        List<Token> tokens = new ArrayList<>(reversed.size());
        for (int i = 0; i < reversed.size(); i++) {
            Node node = reversed.get(i);
            String surface = sentence.substring(
                    charOffsets[node.startCp],
                    charOffsets[node.startCp + node.cpLength]);
            if (node.type == TYPE_KNOWN) {
                String raw = dict.readKnownFeatures(dict.knownPosOffset(node.tokenInfoId));
                tokens.add(parseKnownToken(surface, raw));
            } else {
                String raw = node.tokenInfoId >= 0
                        ? dict.readUnkFeatures(dict.unkPosOffset(node.tokenInfoId))
                        : "";
                tokens.add(parseUnknownToken(surface, raw));
            }
        }
        return tokens;
    }

    private void appendRelaxedNode(
            List<Node>[] nodesEndingAt,
            List<Node> prevNodes,
            int endPos,
            int tokenInfoId,
            short leftId,
            short rightId,
            short wordCost,
            int type,
            int startCp,
            int cpLength) {
        int bestCost = Integer.MAX_VALUE;
        Node bestPrev = null;
        for (int p = 0; p < prevNodes.size(); p++) {
            Node prev = prevNodes.get(p);
            if (prev.shortestCost == Integer.MAX_VALUE) continue;
            int cost = prev.shortestCost + dict.connectionCost(prev.rightId, leftId) + wordCost;
            if (cost < bestCost) {
                bestCost = cost;
                bestPrev = prev;
            }
        }
        if (bestPrev == null) {
            return;
        }
        List<Node> bucket = nodesEndingAt[endPos];
        if (bucket == null) {
            bucket = new ArrayList<>(4);
            nodesEndingAt[endPos] = bucket;
        }
        bucket.add(new Node(tokenInfoId, rightId, type, startCp, cpLength, bestCost, bestPrev));
    }

    static Token parseKnownToken(String surface, String rawFeatures) {
        // Strip leading "surface," explicitly so a comma surface never shifts CSV columns.
        String rest = rawFeatures;
        if (rest.startsWith(surface) && rest.length() > surface.length()
                && rest.charAt(surface.length()) == ',') {
            rest = rest.substring(surface.length() + 1);
        } else {
            int firstComma = rest.indexOf(',');
            rest = firstComma >= 0 ? rest.substring(firstComma + 1) : "";
        }
        String[] cols = splitCsvFast(rest, 9);
        String pos = cleanFeature(cols[0]);
        String posDetail1 = cleanFeature(cols[1]);
        String basicForm = cleanFeature(cols[6]);
        String reading = cleanFeature(cols[7]);
        String pronunciation = cleanFeature(cols[8]);
        return new Token(
                surface,
                pos,
                posDetail1,
                basicForm.isEmpty() ? surface : basicForm,
                reading,
                pronunciation,
                true);
    }

    static Token parseUnknownToken(String surface, String rawFeatures) {
        int firstComma = rawFeatures.indexOf(',');
        String rest = firstComma >= 0 ? rawFeatures.substring(firstComma + 1) : "";
        String[] cols = splitCsvFast(rest, 7);
        String pos = cleanFeature(cols[0]);
        String posDetail1 = cleanFeature(cols[1]);
        String basicForm = cleanFeature(cols[6]);
        return new Token(
                surface,
                pos.isEmpty() ? "名詞" : pos,
                posDetail1,
                basicForm.isEmpty() ? surface : basicForm,
                "",
                "",
                false);
    }

    private static String[] splitCsvFast(String s, int count) {
        String[] out = new String[count];
        int start = 0;
        int col = 0;
        final int len = s.length();
        while (col < count && start <= len) {
            int comma = s.indexOf(',', start);
            if (comma < 0) {
                out[col++] = s.substring(start);
                break;
            }
            out[col++] = s.substring(start, comma);
            start = comma + 1;
        }
        while (col < count) {
            out[col++] = "";
        }
        return out;
    }

    private static String cleanFeature(String f) {
        if (f == null || f.isEmpty() || "*".equals(f)) {
            return "";
        }
        return f;
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.romanizers.CjkWordGroups;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictionaryStore;
import app.morphe.extension.music.patches.lyrics.romanizers.KuromojiDictStore;
import app.morphe.extension.shared.Logger;

/** Local, offline word-cloud builder. */
public final class WordCloudBuilder {

    /** Pixel coordinates; no Android types so it stays unit-testable. */
    public record PlacedWord(String word, float sizePx, float leftPx, float topPx) {
    }

    private static final Pattern PUNCTUATION = Pattern.compile("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$");
    private static final Pattern NUMBERED_WEIGHTED = Pattern.compile(
            "^(?:Line\\s+)?(\\d{1,4})[.)\\]\\-]\\s*(.*?)\\s*\\|\\s*(\\d{1,2})\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERED_PLAIN = Pattern.compile(
            "^(?:Line\\s+)?(\\d{1,4})[.)\\]\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final float[] SIZE_LADDER = {11f, 13f, 15.5f, 18f, 22f};

    private WordCloudBuilder() {
    }

    /** Whether a local CJK dictionary is ready (no download triggered). */
    public static boolean hasLocalDictionary() {
        try {
            return KuromojiDictStore.isReady() || JapaneseDictionaryStore.isReady();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Word cloud dictionary check failed", ex);
            return false;
        }
    }

    /** Prefers the local CJK dictionary, then ICU/JDK word breaks for scripts without one. */
    @Nullable
    public static List<String> tokenize(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        int[] groups = CjkWordGroups.forLine(line);
        if (groups != null && groups.length == line.length()) {
            int start = 0;
            for (int i = 1; i <= groups.length; i++) {
                if (i == groups.length || groups[i] != groups[start]) {
                    out.add(line.substring(start, i));
                    start = i;
                }
            }
        } else {
            // ICU-style word breaks (JDK BreakIterator mirrors ICU for Latin scripts).
            java.text.BreakIterator breaks = java.text.BreakIterator.getWordInstance(Locale.ROOT);
            breaks.setText(line);
            int start = breaks.first();
            for (int end = breaks.next(); end != java.text.BreakIterator.DONE;
                 start = end, end = breaks.next()) {
                if (end > start) {
                    out.add(line.substring(start, end));
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    public static List<WeightedWord> build(List<String> lines, int maxWords,
                                           Collection<String> stopwords,
                                           @Nullable Collection<String> banned) {
        if (lines == null || lines.isEmpty() || maxWords <= 0) {
            return List.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            List<String> tokens = tokenize(line);
            if (tokens == null) continue;
            for (String token : tokens) {
                String cleaned = PUNCTUATION.matcher(token).replaceAll("")
                        .toLowerCase(Locale.ROOT).trim();
                if (cleaned.length() < 2 || WordCloudStopwords.isStopword(cleaned, stopwords)) {
                    continue;
                }
                if (banned != null && banned.contains(cleaned)) {
                    continue;
                }
                counts.merge(cleaned, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().length(), Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        int n = Math.min(maxWords, ranked.size());
        List<WeightedWord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int weight = Math.max(1, 5 - (i * 4) / Math.max(1, n - 1));
            out.add(new WeightedWord(ranked.get(i).getKey(), weight));
        }
        return out;
    }

    /** Converts legacy dot-joined keyword caches back into weighted lines. */
    public static String normalizeCachedKeywords(@Nullable String cached) {
        if (cached == null || cached.indexOf('\n') >= 0 || cached.indexOf('|') >= 0) {
            return cached;
        }
        String[] parts = cached.split("\\s*·\\s*");
        if (parts.length <= 1) {
            return cached;
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String word = part.trim();
            if (word.isEmpty()) {
                continue;
            }
            count++;
            out.append(count).append(". ").append(word).append('|')
                    .append(Math.max(1, 5 - ((count - 1) * 4) / Math.max(1, parts.length - 1)))
                    .append('\n');
        }
        return out.toString();
    }

    /** Parses weighted AI keyword lines; missing weights fall back to rank. */
    @Nullable
    public static List<WeightedWord> parseWeightedKeywords(@Nullable String response, int maxItems) {
        if (response == null || maxItems <= 0) {
            return null;
        }
        String[] raw = response.split("\n", -1);
        List<WeightedWord> out = new ArrayList<>(maxItems);
        int fallbackRank = 0;
        for (String line : raw) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) continue;
            String word = t;
            int weight = -1;
            var m = NUMBERED_WEIGHTED.matcher(t);
            if (m.matches()) {
                word = m.group(2);
                try {
                    weight = Integer.parseInt(m.group(3));
                } catch (NumberFormatException ignored) {
                }
            } else {
                var plain = NUMBERED_PLAIN.matcher(t);
                if (plain.matches()) {
                    word = plain.group(2);
                } else {
                    var pipe = t.split("\\|", 2);
                    if (pipe.length == 2) {
                        word = pipe[0];
                        try {
                            weight = Integer.parseInt(pipe[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            word = word.replaceFirst("^[-*•]\\s*", "").trim();
            if (word.isEmpty() || "null".equalsIgnoreCase(word)
                    || OpenAIClient.isSkipToken(word)) continue;
            if (weight < 1 || weight > 5) {
                weight = Math.max(1, 5 - (fallbackRank * 4) / Math.max(1, maxItems - 1));
            }
            out.add(new WeightedWord(word, weight));
            fallbackRank++;
            if (out.size() >= maxItems) break;
        }
        out.sort(Comparator.comparingInt(WeightedWord::weight).reversed());
        return out.isEmpty() ? null : out;
    }

    /** Float math keeps the layout unit-testable without Android; long words shrink before being skipped. */
    public static List<PlacedWord> layoutCloud(List<WeightedWord> words,
                                               float x, float y, float width, float height,
                                               float scale) {
        List<PlacedWord> out = new ArrayList<>();
        if (words == null || words.isEmpty() || width <= 0 || height <= 0) {
            return out;
        }
        List<float[]> occupied = new ArrayList<>();
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        // Spiral density must follow the destination rect, not the canvas scale, so the same
        // layout fills both the 900px preview and the 1440px export identically.
        float radiusStep = Math.max(0.8f, Math.min(width, height) / 120f);
        float gap = 3f * scale;
        for (WeightedWord word : words) {
            if (word == null || word.word() == null || word.word().isEmpty()) continue;
            float size = SIZE_LADDER[Math.max(0, Math.min(4, word.weight() - 1))] * scale;
            float minSize = Math.max(7f, 8f * scale);
            PlacedWord placed = null;
            for (int shrink = 0; shrink < 6 && placed == null; shrink++) {
                float w = measureWord(word.word(), size);
                float h = size * 1.25f;
                for (int step = 0; step < 600; step++) {
                    double angle = step * 0.35;
                    double radius = step * radiusStep;
                    float left = (float) (cx + Math.cos(angle) * radius - w / 2f);
                    float top = (float) (cy + Math.sin(angle) * radius - h / 2f);
                    if (left < x || top < y || left + w > x + width || top + h > y + height) {
                        continue;
                    }
                    boolean collides = false;
                    for (float[] r : occupied) {
                        if (left - gap < r[2] && left + w + gap > r[0]
                                && top - gap < r[3] && top + h + gap > r[1]) {
                            collides = true;
                            break;
                        }
                    }
                    if (!collides) {
                        occupied.add(new float[]{left, top, left + w, top + h});
                        placed = new PlacedWord(word.word(), size, left, top + size);
                        break;
                    }
                }
                if (placed == null && size > minSize) {
                    size = Math.max(minSize, size * 0.88f);
                } else {
                    break;
                }
            }
            if (placed != null) {
                out.add(placed);
            }
        }
        return out;
    }

    /** Approximate width; avoids Android font metrics so layout stays testable. */
    private static float measureWord(String word, float size) {
        float w = 0f;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            w += (cp > 0x2E80) ? size : size * 0.56f;
            i += Character.charCount(cp);
        }
        return w;
    }
}

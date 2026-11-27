/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WordCloudBuilderTest {

    @Test
    public void stopwordsParseAndFilter() {
        Set<String> set = WordCloudStopwords.parse("the, and, 的, 了");
        assertTrue(WordCloudStopwords.isStopword("THE", set));
        assertTrue(WordCloudStopwords.isStopword("的", set));
        assertTrue(!WordCloudStopwords.isStopword("love", set));
        assertTrue(WordCloudStopwords.isStopword("the", WordCloudStopwords.parse("")));
    }

    @Test
    public void legacyDotCachedKeywordsBecomeSeparateWords() {
        String normalized = WordCloudBuilder.normalizeCachedKeywords("love  ·  dream  ·  night");
        List<WeightedWord> words = WordCloudBuilder.parseWeightedKeywords(normalized, 12);
        assertEquals(3, words.size());
        assertEquals("love", words.get(0).word());
        assertEquals("dream", words.get(1).word());
        assertEquals("night", words.get(2).word());
    }

    @Test
    public void defaultStopwordsCoverContractionsAndCjk() {
        Set<String> stops = WordCloudStopwords.parse("");
        assertTrue(stops.contains("you're"));
        assertTrue(stops.contains("i'm"));
        assertTrue(stops.contains("can't"));
        assertTrue(stops.contains("re"));
        assertTrue(stops.contains("我们"));
        assertTrue(stops.contains("です"));
        assertTrue(stops.contains("그리고"));
        assertFalse(stops.contains("love"));
    }

    @Test
    public void parseWeightedKeywordsHandlesWeightsAndFallback() {
        String response = "1. love|5\n2. dream|3\n3. night|1\n4. plain";
        List<WeightedWord> words = WordCloudBuilder.parseWeightedKeywords(response, 5);
        assertNotNull(words);
        assertEquals(4, words.size());
        assertEquals("love", words.get(0).word());
        assertEquals(5, words.get(0).weight());
        assertEquals("dream", words.get(1).word());
        assertEquals(3, words.get(1).weight());
        assertTrue(words.get(3).weight() >= 1 && words.get(3).weight() <= 5);
        assertNull(WordCloudBuilder.parseWeightedKeywords("SKIP\n", 5));
    }

    @Test
    public void buildCountsFiltersAndCaps() {
        List<WeightedWord> words = WordCloudBuilder.build(
                List.of("love dream love", "dream night", "the love of night"),
                4, WordCloudStopwords.parse("the of"), null);
        assertEquals(3, words.size());
        assertEquals("love", words.get(0).word()); // appears 3 times
        for (WeightedWord w : words) {
            assertTrue(w.weight() >= 1 && w.weight() <= 5);
        }
        List<WeightedWord> banned = WordCloudBuilder.build(
                List.of("love love dream"), 5, WordCloudStopwords.parse(""), Set.of("love"));
        assertEquals(1, banned.size());
        assertEquals("dream", banned.get(0).word());
    }

    @Test
    public void layoutStaysInBoundsAndShrinksLongWords() {
        List<WeightedWord> words = List.of(
                new WeightedWord("supercalifragilistic", 5),
                new WeightedWord("love", 4),
                new WeightedWord("dream", 3),
                new WeightedWord("night", 2),
                new WeightedWord("sky", 1));
        List<WordCloudBuilder.PlacedWord> placed =
                WordCloudBuilder.layoutCloud(words, 0f, 0f, 200f, 160f, 1f);
        assertTrue(placed.size() >= 3);
        for (WordCloudBuilder.PlacedWord p : placed) {
            assertTrue(p.leftPx() >= 0f);
            assertTrue(p.topPx() >= 0f);
            assertTrue(p.sizePx() >= 7f);
        }
    }
}

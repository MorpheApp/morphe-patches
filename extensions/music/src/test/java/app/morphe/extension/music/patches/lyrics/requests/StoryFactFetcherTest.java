/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class StoryFactFetcherTest {

    @Test
    public void clipAtSentenceKeepsCompleteSentence() {
        String clipped = StoryFactFetcher.clipAtSentence(
                "First sentence here. Second sentence follows. Third one.", 20);
        assertEquals("First sentence here.", clipped);
    }

    @Test
    public void clipAtSentenceFallsBackToWordBoundary() {
        String clipped = StoryFactFetcher.clipAtSentence("one two three four five", 14);
        assertEquals("one two three", clipped);
        assertTrue(!clipped.endsWith("thre"));
    }

    @Test
    public void clipAtSentenceHandledCjkPunctuation() {
        assertEquals("这是第一句。", StoryFactFetcher.clipAtSentence("这是第一句。这是第二句。", 8));
    }

    @Test
    public void mergeCapsFactsAtSentenceBoundary() {
        String longText = ("A short complete sentence. ").repeat(400);
        StoryFactFetcher.Facts facts = StoryFactFetcher.merge(List.of(
                new StoryFactFetcher.Contribution(longText, "Genius", true),
                new StoryFactFetcher.Contribution("Last.fm text", "Last.fm", false),
                new StoryFactFetcher.Contribution("Wikipedia text", "Wikipedia", false),
                new StoryFactFetcher.Contribution("MusicBrainz text", "MusicBrainz", false)));
        assertTrue(facts.text().length() <= StoryFactFetcher.MAX_FACTS_CHARS);
        assertTrue(facts.text().contains("A short complete sentence."));
        assertTrue(facts.text().contains("Last.fm text"));
        assertTrue(facts.verified());
        assertTrue(facts.sourceLabel().contains("Genius"));
        assertTrue(facts.sourceLabel().contains("Last.fm"));
        assertTrue(facts.sourceLabel().contains("Wikipedia"));
        assertTrue(facts.sourceLabel().contains("MusicBrainz"));
    }
}

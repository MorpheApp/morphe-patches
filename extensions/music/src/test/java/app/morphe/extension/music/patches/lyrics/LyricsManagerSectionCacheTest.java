/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsManagerSectionCacheTest {

    private static Lyrics lyrics(String part0, String part2, String text3) {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000L, 2000L, "Hello", List.of(), null, false, false, part0),
                new LyricsLine(2000L, 3000L, "World", List.of(), null, false, false, null),
                new LyricsLine(3000L, 4000L, "Again", List.of(), null, false, false, part2),
                new LyricsLine(4000L, 5000L, text3, List.of(), null, false, false, null));
        return new Lyrics(lines, "Apple", true, null, null, null, null, null, null, null);
    }

    @Test
    public void sectionLabelsArePartOfTheCacheKey() {
        String a = LyricsManager.getSb(lyrics("Verse", "Chorus", "Bridge"), false).toString();
        String b = LyricsManager.getSb(lyrics("Intro", "Chorus", "Bridge"), false).toString();
        String c = LyricsManager.getSb(lyrics("Verse", "Outro", "Bridge"), false).toString();
        assertNotEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    public void everyLineTextIsPartOfTheCacheKey() {
        String a = LyricsManager.getSb(lyrics("Verse", null, "Bridge"), false).toString();
        String b = LyricsManager.getSb(lyrics("Verse", null, "Refrain"), false).toString();
        assertNotEquals(a, b);
        assertEquals(a, LyricsManager.getSb(lyrics("Verse", null, "Bridge"), false).toString());
    }
}

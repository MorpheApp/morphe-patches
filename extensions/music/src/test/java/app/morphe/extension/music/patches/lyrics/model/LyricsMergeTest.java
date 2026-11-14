/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;

public class LyricsMergeTest {

    public static final String BG_PREFIX = "bg:";

    @Test
    public void backgroundTrackReturnsMatchingEntry() {
        Map<String, List<LyricsLine>> tracks = new HashMap<>();
        tracks.put(BG_PREFIX + "ja", List.of(new LyricsLine(0, "bg text")));
        assertEquals(1, Objects.requireNonNull(LyricsMerge.backgroundTrack(tracks, "ja")).size());
    }

    @Test
    public void backgroundTrackReturnsNullWhenMissing() {
        Map<String, List<LyricsLine>> tracks = new HashMap<>();
        assertNull(LyricsMerge.backgroundTrack(tracks, "ja"));
        assertNull(LyricsMerge.backgroundTrack(null, "ja"));
    }

    @Test
    public void hasBackgroundTrackDetectsPrefixKeys() {
        Map<String, List<LyricsLine>> tracks = new HashMap<>();
        tracks.put(BG_PREFIX + "ja", List.of(new LyricsLine(0, "bg")));
        assertTrue(LyricsMerge.hasBackgroundTrack(LyricsFixtures.synced(), tracks));
    }

    @Test
    public void hasBackgroundTrackReturnsTrueForTtml() {
        Lyrics ttml = new Lyrics(List.of(), "p", false,
                null, null, null, null, null, "ttml", null);
        assertTrue(LyricsMerge.hasBackgroundTrack(ttml, null));
    }

    @Test
    public void hasBackgroundTrackFalseWhenNoBgKeys() {
        Map<String, List<LyricsLine>> tracks = new HashMap<>();
        tracks.put("ja", List.of(new LyricsLine(0, "text")));
        assertFalse(LyricsMerge.hasBackgroundTrack(LyricsFixtures.synced(), tracks));
    }

    @Test
    public void textAtReturnsTextOrEmpty() {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "hello"), new LyricsLine(0, "world"));
        assertEquals("hello", LyricsMerge.textAt(lines, 0));
        assertEquals("world", LyricsMerge.textAt(lines, 1));
        assertEquals("", LyricsMerge.textAt(lines, -1));
        assertEquals("", LyricsMerge.textAt(lines, 2));
        assertEquals("", LyricsMerge.textAt(null, 0));
    }

    @Test
    public void hasTextTrueWhenAnyLineHasText() {
        assertTrue(LyricsMerge.hasText(List.of(new LyricsLine(0, ""), new LyricsLine(0, "text"))));
        assertFalse(LyricsMerge.hasText(List.of(new LyricsLine(0, ""), new LyricsLine(0, ""))));
        assertFalse(LyricsMerge.hasText(null));
        assertFalse(LyricsMerge.hasText(List.of()));
    }

    @Test
    public void anyWordHasRomajiTrueWhenWordHasRomaji() {
        LyricsLine line = new LyricsLine(0, 100, "text",
                List.of(new Word(0, 50, "text", "romaji", false)), null, false, false, null);
        assertTrue(LyricsMerge.anyWordHasRomaji(List.of(line)));
    }

    @Test
    public void anyWordHasRomajiFalseWhenNoRomaji() {
        LyricsLine line = new LyricsLine(0, 100, "text",
                List.of(new Word(0, 50, "text")), null, false, false, null);
        assertFalse(LyricsMerge.anyWordHasRomaji(List.of(line)));
    }

    @Test
    public void singleLanguageTranslationsWrapsNonEmptyLines() {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "text"));
        Map<String, List<LyricsLine>> map = LyricsMerge.singleLanguageTranslations(lines, "ja");
        assertEquals(1, Objects.requireNonNull(map).size());
        assertSame(lines, map.get("ja"));
    }

    @Test
    public void singleLanguageTranslationsNullForEmptyLines() {
        assertNull(LyricsMerge.singleLanguageTranslations(List.of(new LyricsLine(0, "")), "ja"));
        assertNull(LyricsMerge.singleLanguageTranslations(null, "ja"));
    }

    @Test
    public void addCreditAppendsNewEntry() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "text"));
        Lyrics updated = LyricsMerge.addCredit(lyrics, "John Doe");
        assertEquals(List.of("John Doe"), Objects.requireNonNull(updated).credits());
    }

    @Test
    public void addCreditSkipsDuplicate() {
        Lyrics lyrics = new Lyrics(List.of(new LyricsLine(0, "text")), "p", false,
                null, null, null, null, List.of("John Doe"), null, null, null, null, null);
        Lyrics updated = LyricsMerge.addCredit(lyrics, "John Doe");
        assertSame(lyrics, updated);
    }

    @Test
    public void addCreditHandlesNull() {
        assertNull(LyricsMerge.addCredit(null, "entry"));
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "text"));
        assertSame(lyrics, LyricsMerge.addCredit(lyrics, null));
    }

    @Test
    public void mergeRomanizationReturnsEmptyLinesWhenNoAuxiliary() {
        List<LyricsLine> original = List.of(new LyricsLine(0, "hello"));
        List<LyricsLine> result = LyricsMerge.mergeRomanization(original, null);
        assertEquals(1, result.size());
        assertEquals("", result.get(0).text());
    }

    @Test
    public void mergeRomanizationAlignsAuxiliaryToOriginal() {
        List<LyricsLine> original = List.of(
                new LyricsLine(0, "line one"),
                new LyricsLine(2000, "line two"));
        List<LyricsLine> auxiliary = List.of(
                new LyricsLine(0, "romanized one"),
                new LyricsLine(2000, "romanized two"));
        List<LyricsLine> result = LyricsMerge.mergeRomanization(original, auxiliary);
        assertEquals(2, result.size());
        assertEquals("romanized one", result.get(0).text());
        assertEquals("romanized two", result.get(1).text());
    }
}

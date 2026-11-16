/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

@SuppressWarnings("DataFlowIssue")
public class SectionDetectorTest {

    // ── needsDetection ──

    @Test
    public void needsDetectionFalseForNull() {
        assertFalse(SectionDetector.needsDetection(null));
    }

    @Test
    public void needsDetectionFalseForNotFound() {
        assertFalse(SectionDetector.needsDetection(Lyrics.NOT_FOUND));
    }

    @Test
    public void needsDetectionFalseForEmpty() {
        assertFalse(SectionDetector.needsDetection(LyricsFixtures.synced()));
    }

    @Test
    public void needsDetectionTrueWhenNoSectionLabels() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"));
        assertTrue(SectionDetector.needsDetection(lyrics));
    }

    @Test
    public void needsDetectionFalseWhenAnyLineHasSection() {
        Lyrics lyrics = new Lyrics(List.of(
                new LyricsLine(0, "first line"),
                new LyricsLine(0, LyricsLine.NO_TIME, "second line", List.of(),
                        null, false, false, "Verse")),
                "p", true, null, null, null, null, null, null, null);
        assertFalse(SectionDetector.needsDetection(lyrics));
    }

    @Test
    public void needsDetectionFalseWhenSectionIsEmptyString() {
        Lyrics lyrics = new Lyrics(List.of(
                new LyricsLine(0, LyricsLine.NO_TIME, "first line", List.of(),
                        null, false, false, "")),
                "p", true, null, null, null, null, null, null, null);
        assertTrue(SectionDetector.needsDetection(lyrics));
    }

    // ── applySections ──

    @Test
    public void applySectionsNullReturnsNull() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "text"));
        assertNull(SectionDetector.applySections(lyrics, null));
    }

    @Test
    public void applySectionsLabelsLines() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"),
                new LyricsLine(2000, "third line"));
        List<String> sections = Arrays.asList("Verse", null, "Chorus");
        Lyrics result = SectionDetector.applySections(lyrics, sections);
        assertEquals("Verse", result.lines().get(0).songPart());
        assertNull(result.lines().get(1).songPart());
        assertEquals("Chorus", result.lines().get(2).songPart());
    }

    @Test
    public void applySectionsSkipTokenBecomesNull() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"));
        List<String> sections = List.of("Verse", "SKIP");
        Lyrics result = SectionDetector.applySections(lyrics, sections);
        assertEquals("Verse", result.lines().get(0).songPart());
        assertNull(result.lines().get(1).songPart());
    }

    @Test
    public void applySectionsEmptyStringBecomesNull() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line"));
        List<String> sections = List.of("");
        // Empty label becomes null, which matches the original, so no update.
        Lyrics result = SectionDetector.applySections(lyrics, sections);
        assertNull(result);
    }

    @Test
    public void applySectionsNoChangeReturnsNull() {
        Lyrics lyrics = new Lyrics(List.of(
                new LyricsLine(0, LyricsLine.NO_TIME, "first line", List.of(),
                        null, false, false, "Verse")),
                "p", true, null, null, null, null, null, null, null);
        List<String> sections = List.of("Verse");
        assertNull(SectionDetector.applySections(lyrics, sections));
    }

    @Test
    public void applySectionsShorterThanLines() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"),
                new LyricsLine(2000, "third line"));
        List<String> sections = List.of("Verse");
        Lyrics result = SectionDetector.applySections(lyrics, sections);
        assertEquals("Verse", result.lines().get(0).songPart());
        assertNull(result.lines().get(1).songPart());
        assertNull(result.lines().get(2).songPart());
    }

    @Test
    public void applySectionsPreservesOtherFields() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"));
        List<String> sections = List.of("Verse", "Chorus");
        Lyrics result = SectionDetector.applySections(lyrics, sections);
        assertEquals("TestProvider", result.providerName());
        assertTrue(result.synced());
    }
}

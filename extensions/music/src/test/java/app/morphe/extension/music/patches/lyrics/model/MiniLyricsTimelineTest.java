/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class MiniLyricsTimelineTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void emptyStringIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker(""));
    }

    @Test
    public void whitespaceOnlyIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("   "));
    }

    @Test
    public void singleMusicalNoteIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("🎵"));
    }

    @Test
    public void multipleMusicalNotesIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("🎶"));
    }

    @Test
    public void multipleNotesWithSpaceIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("🎵 🎵 🎵"));
    }

    @Test
    public void quarterNoteIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("♩"));
    }

    @Test
    public void eighthNoteIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("♬"));
    }

    @Test
    public void textIsNotInstrumental() {
        assertFalse(MiniLyricsTimeline.isInstrumentalMarker("hello"));
    }

    @Test
    public void noteWithTextIsNotInstrumental() {
        assertFalse(MiniLyricsTimeline.isInstrumentalMarker("🎵 hello"));
    }

    @Test
    public void textWithNoteIsNotInstrumental() {
        assertFalse(MiniLyricsTimeline.isInstrumentalMarker("hello 🎵"));
    }

    @Test
    public void whitespaceWithNoteIsInstrumental() {
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("  🎵  "));
    }

    @Test
    public void emojiPresentationSelectorIgnored() {
        // U+FE0F is the emoji presentation selector, should be ignored
        assertTrue(MiniLyricsTimeline.isInstrumentalMarker("🎵\uFE0F"));
    }

    @Test
    public void atReturnsNullWhenNoLines() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(List.of(), 10000);
        MiniLyricsTimeline.Selection selection = timeline.at(0);
        assertNotNull(selection);
        assertNull(selection.vocal());
        assertNull(selection.gap());
        assertNull(selection.previous());
    }

    @Test
    public void atBeforeFirstLineReturnsPreviousNull() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(5000, "first line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(0);
        assertNotNull(selection);
        assertNull(selection.vocal());
        assertNull(selection.previous());
    }

    @Test
    public void atOnFirstLineReturnsVocal() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "first line"),
                        new LyricsLine(5000, "second line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(0);
        assertNotNull(selection);
        assertNotNull(selection.vocal());
        assertEquals("first line", selection.vocal().line().text());
        assertNull(selection.gap());
    }

    @Test
    public void atOnMiddleLineReturnsCorrectVocal() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "first line"),
                        new LyricsLine(5000, "second line"),
                        new LyricsLine(10000, "third line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(7000);
        assertNotNull(selection);
        assertNotNull(selection.vocal());
        assertEquals("second line", selection.vocal().line().text());
    }

    @Test
    public void atOnInstrumentalMarkerLineReturnsGap() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "first line"),
                        new LyricsLine(5000, "🎵"),
                        new LyricsLine(10000, "third line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(5500);
        assertNotNull(selection);
    }

    @Test
    public void atAfterLastLineReturnsLastVocal() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "first line"),
                        new LyricsLine(5000, "second line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(15000);
        assertNotNull(selection);
        // At 15000ms the second line's vocal (5000-20000) is still active, so vocal is set.
        assertNotNull(selection.vocal());
        assertEquals("second line", selection.vocal().line().text());
    }

    @Test
    public void atClampsNegativeToZero() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "first line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(-100);
        assertNotNull(selection);
    }

    @Test
    public void atClampsBeyondDuration() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "first line")), 10000);
        MiniLyricsTimeline.Selection selection = timeline.at(99999);
        assertNotNull(selection);
    }

    @Test
    public void atBackgroundLinePrefersLeadVocal() {
        MiniLyricsTimeline timeline = new MiniLyricsTimeline(
                List.of(new LyricsLine(0, "lead line"),
                        new LyricsLine(0, "bg line", List.of(), null, false, true),
                        new LyricsLine(5000, "next line")), 20000);
        MiniLyricsTimeline.Selection selection = timeline.at(100);
        assertNotNull(selection);
        assertNotNull(selection.vocal());
        assertFalse(selection.vocal().line().isBG());
    }
}

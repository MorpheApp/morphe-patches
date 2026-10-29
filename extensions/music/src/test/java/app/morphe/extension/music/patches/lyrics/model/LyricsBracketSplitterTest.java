package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class LyricsBracketSplitterTest {

    private static Lyrics synced(LyricsLine... lines) {
        return new Lyrics(List.of(lines), "test", true, null, null, null, null, null, null, null);
    }

    private static LyricsLine background(long startMs, String text) {
        return new LyricsLine(startMs, text, List.of(), null, false, true);
    }

    @Test public void bracketedVocalMovesToALineOfItsOwn() {
        List<LyricsLine> lines = LyricsBracketSplitter.apply(
                synced(new LyricsLine(1000, "I want you (oh)"))).lines();
        assertEquals(2, lines.size());
        assertEquals("I want you", lines.get(0).text());
        assertFalse(lines.get(0).isBG());
        assertEquals("oh", lines.get(1).text());
        assertTrue(lines.get(1).isBG());
    }

    @Test public void wordTimingsFollowTheirSide() {
        LyricsLine line = new LyricsLine(1000, 3000, "I want you (oh)", List.of(
                new Word(1000, 1400, "I ", null, true),
                new Word(1400, 1900, "want ", null, true),
                new Word(1900, 2400, "you ", null, true),
                new Word(2400, 3000, "(oh)")));
        List<LyricsLine> lines = LyricsBracketSplitter.apply(synced(line)).lines();
        assertEquals(2, lines.size());
        assertEquals("I want you", lines.get(0).text());
        assertEquals(3, lines.get(0).words().size());
        assertEquals("oh", lines.get(1).text());
        assertEquals(1, lines.get(1).words().size());
        assertEquals(2400, lines.get(1).startTimeMs());
    }

    @Test public void backgroundLineIsNotCutIntoALead() {
        List<LyricsLine> lines = LyricsBracketSplitter.apply(synced(
                new LyricsLine(0, "Hello"), background(500, "(ooh) yeah"))).lines();
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).isBG());
    }

    @Test public void wholeBracketedLineBecomesBackgroundOfTheLeadAbove() {
        List<LyricsLine> lines = LyricsBracketSplitter.apply(synced(
                new LyricsLine(0, "Hello"), new LyricsLine(1000, "(Ooh, yeah)"))).lines();
        assertEquals(2, lines.size());
        assertEquals("Ooh, yeah", lines.get(1).text());
        assertTrue(lines.get(1).isBG());
    }

    @Test public void sectionNamesAndTagsStayAsTheyAre() {
        List<LyricsLine> lines = LyricsBracketSplitter.apply(synced(
                new LyricsLine(0, "Hello"), new LyricsLine(1000, "(Chorus)"),
                new LyricsLine(2000, "【Live】 Hello (oh)"))).lines();
        assertEquals(3, lines.size());
        assertEquals("(Chorus)", lines.get(1).text());
        assertFalse(lines.get(1).isBG());
        assertEquals("【Live】 Hello (oh)", lines.get(2).text());
    }

    @Test public void singleWordLineWithWordTimingHasWordTimings() {
        LyricsLine singleWord = new LyricsLine(1000, 2000, "Yeah", List.of(new Word(1000, 2000, "Yeah")));
        assertTrue(singleWord.hasWordTimings());

        LyricsLine fakeWordSync = new LyricsLine(1000, 2000, "I want you", List.of(new Word(1000, 2000, "I want you")));
        assertFalse(fakeWordSync.hasWordTimings());
    }
}

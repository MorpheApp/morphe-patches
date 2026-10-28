/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TrackFormatTest {
    private static final String ELLIPSIS = MiniLyricsFormatter.SPLIT_ELLIPSIS;

    @Test public void variantShownOnlyWhenTheBackendHasOne() {
        assertEquals("Duvet (Acoustic) - bôa", MiniLyricsFormatter.trackLabel(null, "Duvet", "bôa", "Acoustic"));
        assertEquals("Duvet - bôa", MiniLyricsFormatter.trackLabel(null, "Duvet", "bôa", null));
        assertEquals("Duvet - bôa", MiniLyricsFormatter.trackLabel(null, "Duvet", "bôa", "  "));
    }

    @Test public void parenthesesInATitleAreNotAVariant() {
        assertEquals("Rockstar (Olympia) - Artist",
                MiniLyricsFormatter.trackLabel(null, "Rockstar (Olympia)", "Artist", null));
    }

    @Test public void sectionNeedsEveryVariableInside() {
        String template = "{t}: [{v} by {a}]:";
        assertEquals("Duvet [Live by bôa]", MiniLyricsFormatter.trackLabel(template, "Duvet", "bôa", "Live"));
        assertEquals("Duvet", MiniLyricsFormatter.trackLabel(template, "Duvet", "bôa", null));
        assertEquals("Duvet", MiniLyricsFormatter.trackLabel(template, "Duvet", "", "Live"));
    }

    @Test public void escapedColonIsLiteral() {
        assertEquals("Duvet: bôa", MiniLyricsFormatter.trackLabel("{t}\\: {a}", "Duvet", "bôa", null));
    }

    @Test public void placeholderInsideATitleIsKept() {
        assertEquals("{a} - X", MiniLyricsFormatter.trackLabel("{t} - {a}", "{a}", "X", null));
    }

    @Test public void eachTemplateLineIsAFrame() {
        assertEquals(List.of("Duvet (Acoustic)", "bôa"),
                MiniLyricsFormatter.trackFrames("{t} :({v}):\n{a}", "Duvet", "bôa", "Acoustic", 0, 1));
        // A line left empty by a hidden section is not a frame.
        assertEquals(List.of("Duvet", "bôa"),
                MiniLyricsFormatter.trackFrames("{t}\n:{v}:\n{a}", "Duvet", "bôa", null, 0, 1));
    }

    @Test public void longTrackLineIsSplitIntoFramesThatFit() {
        List<String> frames = MiniLyricsFormatter.trackFrames("{t} - {a}",
                "Smells Like Teen Spirit", "Nirvana", null, 10, 1);
        assertTrue(frames.size() > 2);
        for (String frame : frames) {
            assertTrue(frame, MiniLyricsFormatter.width(frame, 1) <= 10);
        }
        assertEquals("Smells Like Teen Spirit - Nirvana", String.join(" ", frames));
    }

    @Test public void splitIsDecidedFromTheWholeLine() {
        // Neither variable is over the limit, but the rendered line is.
        List<String> frames = MiniLyricsFormatter.trackFrames("{t} - {a}", "Duvet", "bôa", null, 8, 1);
        assertEquals(List.of("Duvet -", "bôa"), frames);
    }

    @Test public void fullWidthCharactersCountAsTheMultiplier() {
        String six = "あいうえおか";
        assertEquals(6.0, MiniLyricsFormatter.width(six, 1), 0.001);
        assertEquals(10.8, MiniLyricsFormatter.width(six, 1.8), 0.001);
        assertEquals(1, MiniLyricsFormatter.chunks(new LyricsLine(0, six), 5000, 10, false, false, 1).size());
        assertEquals(2, MiniLyricsFormatter.chunks(new LyricsLine(0, six), 5000, 10, false, false, 1.8).size());
        assertEquals(4.0, MiniLyricsFormatter.width("abcd", 1.8), 0.001);
    }

    @Test public void unspacedSplitGetsOneEllipsis() {
        String text = "あいうえおかきくけこ";
        List<MiniLyricsFormatter.Chunk> chunks =
                MiniLyricsFormatter.chunks(new LyricsLine(0, text), 5000, 5, false, false, 1);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().endsWith(ELLIPSIS));
        assertFalse(chunks.get(1).text().contains(ELLIPSIS));
        assertEquals(text, (chunks.get(0).text() + chunks.get(1).text()).replace(ELLIPSIS, ""));
    }

    @Test public void spacedSplitHasNoEllipsis() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "don't stop me now"), 5000, 10, false, false, 1);
        assertEquals("don't stop", chunks.get(0).text());
        assertEquals("me now", chunks.get(1).text());
    }

    @Test public void hyphenatedAndPunctuatedWordsStayWhole() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "well-known words, again!"), 5000, 10, false, false, 1);
        assertEquals("well-known words,", chunks.get(0).text());
        assertEquals("again!", chunks.get(1).text());
    }

    @Test public void wordTimestampsDecideTheSplit() {
        String text = "今日はいい天気";
        LyricsLine line = new LyricsLine(0, text, List.of(
                new Word(0, 500, "今日は"), new Word(500, 1000, "いい"), new Word(1000, 1500, "天気")));
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(line, 2000, 3, false, false, 1);
        assertEquals(2, chunks.size());
        assertEquals("今日はいい" + ELLIPSIS, chunks.get(0).text());
        assertEquals("天気", chunks.get(1).text());
        assertEquals(1000, chunks.get(1).startMs());
    }
}

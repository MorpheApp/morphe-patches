/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class PairedPunctuationTest {
    private static List<String> split(String input) {
        return MiniLyricsFormatter.chunks(new LyricsLine(0, input), 8000, 1, false, false).stream().map(MiniLyricsFormatter.Chunk::text).toList();
    }

    @Test
    public void userExamples() {
        assertEquals(List.of("(Ooh yeah)", "(Ooh yeah)"), split("(Ooh yeah Ooh yeah)"));
        assertEquals(List.of("\"Well, let me tell you\"", "\"She had pretty eyes\""), split("\"Well, let me tell you She had pretty eyes\""));
    }

    @Test
    public void everyUnicodeAndCldrPair() {
        for (var p : PairedPunctuation.PAIRS)
            assertEquals(p.toString(), List.of(p.opening() + "one two" + p.closing(), p.opening() + "three four" + p.closing()), split(p.opening() + "one two three four" + p.closing()));
    }

    @Test
    public void nestedShellsAndDifferentScripts() {
        assertEquals(List.of("（「你好…」）", "（「世界」）"), split("（「你好世界」）"));
        assertEquals(List.of("«مرحبا بك»", "«يا صديقي»"), split("«مرحبا بك يا صديقي»"));
        assertEquals(List.of("„eins zwei“", "„drei vier“"), split("„eins zwei drei vier“"));
    }

    @Test
    public void independentlyBalancedPhrasesAreNotWrapped() {
        assertEquals(List.of("(one two)", "(three four)"), split("(one two) (three four)"));
        assertEquals(List.of("\"one two\"", "\"three four\""), split("\"one two\" \"three four\""));
    }

    @Test
    public void innerApostrophesDoNotEndOuterQuotes() {
        assertEquals(List.of("'I'm going'", "'you're coming'"), split("'I'm going you're coming'"));
    }

    @Test
    public void noSplitDoesNotRewriteAnything() {
        assertEquals("（hello）", MiniLyricsFormatter.chunks(new LyricsLine(0, "（hello）"), 1000, 100, false, false).get(0).text());
    }

    @Test
    public void wordTimesAndSourceArePreserved() {
        var line = new LyricsLine(0, 8000, "(one two three four)", List.of(new Word(0, 1000, "(one"), new Word(1000, 2000, "two"), new Word(4000, 5000, "three"), new Word(5000, 6000, "four)")));
        var chunks = MiniLyricsFormatter.chunks(line, 8000, 1, false, false);
        assertEquals(4000, chunks.get(1).startMs());
        assertEquals("(one two)", chunks.get(0).text());
        assertEquals("(one two three four)", line.text());
    }

    @Test
    public void repeatCountIsInTheNewDefault() {
        assertEquals("{lrc} :(x{n}):", MiniLyricsFormatter.DEFAULT_ENCAPSULATION);
        assertEquals("line ", MiniLyricsFormatter.encapsulate(null, "line", 1));
        assertEquals("line (x2)", MiniLyricsFormatter.encapsulate(null, "line", 2));
    }

    @Test
    public void negativeOffsetLooksAheadAndPositiveDelays() {
        assertEquals(5600, MiniLyricsFormatter.selectionPosition(5000, -600));
        assertEquals(4400, MiniLyricsFormatter.selectionPosition(5000, 600));
        assertEquals(0, MiniLyricsFormatter.selectionPosition(100, 600));
    }
}

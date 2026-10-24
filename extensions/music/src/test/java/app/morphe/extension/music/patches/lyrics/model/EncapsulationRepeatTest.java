package app.morphe.extension.music.patches.lyrics.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class EncapsulationRepeatTest {
    private static final String TEMPLATE = "# {lrc} # :(x{n}):";

    @Test public void colonSegmentsAppearOnlyForRepeats() {
        assertEquals("# lyric # ", MiniLyricsFormatter.encapsulate(TEMPLATE, "lyric", 1));
        assertEquals("# lyric # (x2)", MiniLyricsFormatter.encapsulate(TEMPLATE, "lyric", 2));
        assertEquals("# lyric # (x3)", MiniLyricsFormatter.encapsulate(TEMPLATE, "lyric", 3));
    }

    @Test public void bracketedCountSyntaxResolves() {
        assertEquals("lyric ", MiniLyricsFormatter.encapsulate("{lrc} :[{n} times]:", "lyric", 1));
        assertEquals("lyric [2 times]", MiniLyricsFormatter.encapsulate("{lrc} :[{n} times]:", "lyric", 2));
    }

    @Test public void escapedColonsStayLiteral() {
        assertEquals(":hi: lyric", MiniLyricsFormatter.encapsulate("\\:hi\\: {lrc}", "lyric", 1));
        assertEquals(":hi: lyric", MiniLyricsFormatter.encapsulate("\\:hi\\: {lrc}", "lyric", 5));
    }

    @Test public void repeatCountCountsConsecutiveRun() {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "a"), new LyricsLine(1000, "a"),
                new LyricsLine(2000, "b"), new LyricsLine(3000, "a"), new LyricsLine(4000, "a"));
        assertEquals(1, MiniLyricsFormatter.repeatCount(lines, 0));
        assertEquals(2, MiniLyricsFormatter.repeatCount(lines, 1));
        assertEquals(1, MiniLyricsFormatter.repeatCount(lines, 3));
        assertEquals(2, MiniLyricsFormatter.repeatCount(lines, 4));
    }
}

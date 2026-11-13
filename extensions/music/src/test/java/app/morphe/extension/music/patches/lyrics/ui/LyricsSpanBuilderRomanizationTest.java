/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsSpanBuilderRomanizationTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    /** No reading on the timings, so the whole-line reading path runs. */
    private static List<WordTiming> perCharacterTimings(String text) {
        List<WordTiming> timings = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            timings.add(new WordTiming(i, i + 1, 0, 1, null, false));
        }
        return timings;
    }

    private static String joinedRomaji(List<WordTiming> timings) {
        StringBuilder out = new StringBuilder();
        for (WordTiming timing : timings) {
            String romaji = timing.romaji();
            if (romaji == null || romaji.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(romaji.trim());
        }
        return out.toString();
    }

    private static List<WordTiming> syllableRomanized(
            String original, String wholeRoman) {
        LyricsLine line = new LyricsLine(0, original);
        List<LyricsLine> romanized = List.of(new LyricsLine(0, wholeRoman));
        return LyricsSpanBuilder.buildSyllableRomanizedTimings(
                line, 0, romanized, false, perCharacterTimings(original), original, false);
    }

    @Test
    public void japaneseOkuriganaDoesNotRepeatReadingTails() {
        // The whole reading has fewer tokens than the line's syllables, so the allocator leaves
        // holes. Their local guides are tails of words already in the reading and must not be filled.
        List<WordTiming> result = syllableRomanized("遠回りしてでも", "Toomawari shite demo");
        assertEquals("Toomawari shite demo", joinedRomaji(result));
    }

    @Test
    public void japaneseOkuriganaDoesNotRepeatReadingTailsOnLongerLine() {
        // A line with kanji whose local guide is absent and kana holes whose guides are tails of
        // the words already in the reading; the tails must not be filled in again.
        List<WordTiming> result =
                syllableRomanized("走り抜ける", "hashiri nukeru");
        assertEquals("hashiri nukeru", joinedRomaji(result));
    }

    @Test
    public void missingSyllableStillGetsLocalGuide() {
        // A syllable the reading left out is a genuine gap, so the local guide still fills it.
        List<WordTiming> result = syllableRomanized("あいう", "a i");
        assertEquals("a i u", joinedRomaji(result));
    }
}

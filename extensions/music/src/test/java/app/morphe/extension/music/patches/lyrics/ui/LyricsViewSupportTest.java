/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.settings.Settings;

public class LyricsViewSupportTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void spotlightAlphaAtZero() {
        assertEquals(0.35f, LyricsViewSupport.spotlightAlpha(0), 0.001f);
    }

    @Test
    public void spotlightAlphaAtOne() {
        assertEquals(0.28f, LyricsViewSupport.spotlightAlpha(1), 0.001f);
    }

    @Test
    public void spotlightAlphaAtTwo() {
        assertEquals(0.20f, LyricsViewSupport.spotlightAlpha(2), 0.001f);
    }

    @Test
    public void spotlightAlphaAtThree() {
        assertEquals(0.14f, LyricsViewSupport.spotlightAlpha(3), 0.001f);
    }

    @Test
    public void spotlightAlphaAtFourOrBeyond() {
        assertEquals(0.10f, LyricsViewSupport.spotlightAlpha(4), 0.001f);
        assertEquals(0.10f, LyricsViewSupport.spotlightAlpha(10), 0.001f);
    }

    @Test
    public void spotlightScaleAtZero() {
        assertEquals(0.97f, LyricsViewSupport.spotlightScale(0), 0.001f);
    }

    @Test
    public void spotlightScaleAtOne() {
        assertEquals(0.97f, LyricsViewSupport.spotlightScale(1), 0.001f);
    }

    @Test
    public void spotlightScaleAtTwo() {
        assertEquals(0.93f, LyricsViewSupport.spotlightScale(2), 0.001f);
    }

    @Test
    public void spotlightScaleAtThree() {
        assertEquals(0.93f, LyricsViewSupport.spotlightScale(3), 0.001f);
    }

    @Test
    public void spotlightScaleAtFour() {
        assertEquals(0.90f, LyricsViewSupport.spotlightScale(4), 0.001f);
    }

    @Test
    public void spotlightScaleAtFiveOrBeyond() {
        assertEquals(0.85f, LyricsViewSupport.spotlightScale(5), 0.001f);
        assertEquals(0.85f, LyricsViewSupport.spotlightScale(10), 0.001f);
    }

    @Test
    public void lineTextSizeSpNonBackground() {
        assertEquals(16, LyricsViewSupport.lineTextSizeSp(false, 16));
    }

    @Test
    public void lineTextSizeSpBackground() {
        // BACKGROUND_TEXT_SIZE_FACTOR = 0.7f
        assertEquals(11, LyricsViewSupport.lineTextSizeSp(true, 16));
    }

    @Test
    public void lineTextSizeSpBackgroundZero() {
        assertEquals(0, LyricsViewSupport.lineTextSizeSp(true, 0));
    }

    @Test
    public void lineTextSizeSpNonBackgroundZero() {
        assertEquals(0, LyricsViewSupport.lineTextSizeSp(false, 0));
    }

    @Test
    public void hasTranslationNullList() {
        List<LyricsLine> originals = List.of(new LyricsLine(0, "hello"));
        assertFalse(LyricsViewSupport.hasTranslation(null, originals));
    }

    @Test
    public void hasTranslationEmptyList() {
        List<LyricsLine> originals = List.of(new LyricsLine(0, "hello"));
        assertFalse(LyricsViewSupport.hasTranslation(List.of(), originals));
    }

    @Test
    public void hasTranslationSameText() {
        List<String> translated = List.of("hello");
        List<LyricsLine> originals = List.of(new LyricsLine(0, "hello"));
        assertFalse(LyricsViewSupport.hasTranslation(translated, originals));
    }

    @Test
    public void hasTranslationDifferentText() {
        List<String> translated = List.of("hola");
        List<LyricsLine> originals = List.of(new LyricsLine(0, "hello"));
        assertTrue(LyricsViewSupport.hasTranslation(translated, originals));
    }

    @Test
    public void hasTranslationNullEntryInList() {
        List<String> translated = java.util.Arrays.asList(null, "hola");
        List<LyricsLine> originals = List.of(new LyricsLine(0, "hello"), new LyricsLine(0, "world"));
        assertTrue(LyricsViewSupport.hasTranslation(translated, originals));
    }

    @Test
    public void hasTranslationAllNullOrEmpty() {
        List<String> translated = java.util.Arrays.asList(null, "");
        List<LyricsLine> originals = List.of(new LyricsLine(0, "hello"), new LyricsLine(0, "world"));
        assertFalse(LyricsViewSupport.hasTranslation(translated, originals));
    }

    @Test
    public void hasTranslationShorterList() {
        List<String> translated = List.of("hola");
        List<LyricsLine> originals = List.of(
                new LyricsLine(0, "hello"), new LyricsLine(0, "world"));
        assertTrue(LyricsViewSupport.hasTranslation(translated, originals));
    }

    @Test
    public void lineScaleAvailableWhenRelaxScrollStoredTrueButSpringScrollOff() {
        boolean prevEnabled = Settings.LYRICS_ENABLED.get();
        boolean prevSpring = Settings.LYRICS_SPRING_SCROLL.get();
        boolean prevRelax = Settings.LYRICS_RELAX_SCROLL.get();
        try {
            Settings.LYRICS_ENABLED.save(true);
            Settings.LYRICS_SPRING_SCROLL.save(true);
            Settings.LYRICS_RELAX_SCROLL.save(true);
            assertFalse(Settings.LYRICS_LINE_SCALE.isAvailable());

            Settings.LYRICS_SPRING_SCROLL.save(false);
            assertTrue(Settings.LYRICS_LINE_SCALE.isAvailable());
        } finally {
            Settings.LYRICS_ENABLED.save(prevEnabled);
            Settings.LYRICS_SPRING_SCROLL.save(prevSpring);
            Settings.LYRICS_RELAX_SCROLL.save(prevRelax);
        }
    }

    @Test
    public void sameLineStructureDistinguishesDifferentCandidatesWithSameLineCount() {
        Lyrics base = new Lyrics(
                List.of(new LyricsLine(1000L, 3000L, "line one", List.of(), null, false, false, null)),
                "Musixmatch", true, null, null, null, null, null, null, null, null, null);
        Lyrics withRuby = base.withLines(List.of(new LyricsLine(
                1000L, 3000L, "line one",
                List.of(new Word(1000L, 2000L, "line", null, true, "rain")),
                null, false, false, "Verse")));
        Lyrics differentCandidate = new Lyrics(
                List.of(new LyricsLine(1500L, 3500L, "other line", List.of(), null, false, false, null)),
                "LRCLIB", true, null, null, null, null, null, null, null, null, null);

        assertTrue(LyricsPanelView.sameLineStructure(base, withRuby, false));
        assertFalse(LyricsPanelView.sameLineStructure(base, differentCandidate, false));
    }
}

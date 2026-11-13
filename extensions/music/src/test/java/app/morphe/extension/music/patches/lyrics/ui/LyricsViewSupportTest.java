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
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

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
}

/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class LyricsScrollEngineFlipTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void translationEmptyReturnsZero() {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        assertEquals(0f, flip.translation(0f), 0.001f);
    }

    @Test
    public void hasActiveLayersEmptyReturnsFalse() {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        assertFalse(flip.hasActiveLayers(0f));
    }

    @Test
    public void resetClearsState() {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        flip.reset(100f);
        assertFalse(flip.hasActiveLayers(0f));
    }

    @Test
    public void translationWithActiveLayerIsNonZero() throws Exception {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        addLayer(flip, 0f, 100f, 0f);
        float translation = flip.translation(0f);
        assertTrue(Math.abs(translation - 100f) < 1f);
    }

    @Test
    public void hasActiveLayersTrueAfterAdd() throws Exception {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        addLayer(flip, 0f, 100f, 0f);
        assertTrue(flip.hasActiveLayers(0f));
    }

    @Test
    public void translationRemovesDoneLayers() throws Exception {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        addLayer(flip, 0f, 100f, 0f);
        float translation = flip.translation(600f);
        assertEquals(0f, translation, 0.001f);
        assertFalse(flip.hasActiveLayers(600f));
    }

    @Test
    public void translationSumsMultipleLayers() throws Exception {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        addLayer(flip, 0f, 50f, 0f);
        addLayer(flip, 0f, 30f, 0f);
        float translation = flip.translation(0f);
        assertTrue(Math.abs(translation - 80f) < 2f);
    }

    @Test
    public void addSkipsSmallOffset() throws Exception {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        addLayer(flip, 0f, 0.4f, 0f);
        assertEquals(0f, flip.translation(0f), 0.001f);
        assertFalse(flip.hasActiveLayers(0f));
    }

    @Test
    public void addSkipsAlreadyFinished() throws Exception {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        SpringEasing.LyricsSpring spring = new SpringEasing.LyricsSpring(100f);
        addLayerRaw(flip, 0f, 100f, -(spring.totalMs + 1), spring);
        assertFalse(flip.hasActiveLayers(0f));
    }

    @Test
    public void lastTopTracksReset() {
        LyricsScrollEngine.LyricsItemFlip flip = new LyricsScrollEngine.LyricsItemFlip();
        flip.reset(250f);
        assertEquals(250f, flip.lastTop, 0.001f);
    }

    private static void addLayer(LyricsScrollEngine.LyricsItemFlip flip,
                                 float nowMs, float offset, float delayMs)
            throws Exception {
        SpringEasing.LyricsSpring spring = new SpringEasing.LyricsSpring(200f);
        addLayerRaw(flip, nowMs, offset, delayMs, spring);
    }

    private static void addLayerRaw(LyricsScrollEngine.LyricsItemFlip flip,
                                    float nowMs, float offset, float delayMs,
                                    SpringEasing.LyricsSpring spring)
            throws Exception {
        Method method = LyricsScrollEngine.LyricsItemFlip.class.getDeclaredMethod(
                "add", float.class, float.class, float.class, SpringEasing.LyricsSpring.class);
        method.setAccessible(true);
        method.invoke(flip, nowMs, offset, delayMs, spring);
    }
}

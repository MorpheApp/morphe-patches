/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.ui.LyricsLineAlphas.State;

public class LyricsLineAlphasTest {

    private static final float DELTA = 1e-5f;

    /** A state with every sweep condition on, overridable per test. */
    private static final class Builder {
        boolean dimmed = true;
        boolean hasWordTimings = true;
        boolean sweepEnabled = true;
        boolean allSung = false;
        boolean activeLine = true;
        boolean lineLookAnimating = false;
        boolean lineLookInitialized = false;
        boolean wordSyncEnabled = true;
        float lookCurAlpha = 0.9f;
        float lookCurUnsungAlpha = 0.4f;
        float lookCurTransAlpha = 0.7f;
        float lookCurReadingAlpha = 0.6f;
        float lineAlpha = 0.5f;
        int baseTextAlpha = 200;

        State build() {
            return new State(dimmed, hasWordTimings, sweepEnabled, allSung, activeLine,
                    lineLookAnimating, lineLookInitialized, wordSyncEnabled,
                    lookCurAlpha, lookCurUnsungAlpha, lookCurTransAlpha, lookCurReadingAlpha,
                    lineAlpha, baseTextAlpha);
        }
    }

    @Test
    public void hasWordSweepAllConditionsTrue() {
        assertTrue(LyricsLineAlphas.hasWordSweep(new Builder().build()));
    }

    @Test
    public void hasWordSweepFalseWhenNotDimmed() {
        Builder b = new Builder();
        b.dimmed = false;
        assertFalse(LyricsLineAlphas.hasWordSweep(b.build()));
    }

    @Test
    public void hasWordSweepFalseWithoutWordTimings() {
        Builder b = new Builder();
        b.hasWordTimings = false;
        assertFalse(LyricsLineAlphas.hasWordSweep(b.build()));
    }

    @Test
    public void hasWordSweepFalseWhenSweepDisabled() {
        Builder b = new Builder();
        b.sweepEnabled = false;
        assertFalse(LyricsLineAlphas.hasWordSweep(b.build()));
    }

    @Test
    public void hasWordSweepFalseWhenAllSung() {
        Builder b = new Builder();
        b.allSung = true;
        assertFalse(LyricsLineAlphas.hasWordSweep(b.build()));
    }

    @Test
    public void hasWordSweepTrueWhileAnimatorRunsOnInactiveLine() {
        Builder b = new Builder();
        b.activeLine = false;
        b.lineLookAnimating = true;
        assertTrue(LyricsLineAlphas.hasWordSweep(b.build()));
    }

    @Test
    public void hasWordSweepFalseWhenInactiveAndNotAnimating() {
        Builder b = new Builder();
        b.activeLine = false;
        b.lineLookAnimating = false;
        assertFalse(LyricsLineAlphas.hasWordSweep(b.build()));
    }

    @Test
    public void unsungMainAlphaInitializedSweptUsesUnsungLookAlpha() {
        Builder b = new Builder();
        b.lineLookInitialized = true;
        assertEquals(0.4f, LyricsLineAlphas.unsungMainAlpha(b.build(), true), DELTA);
    }

    @Test
    public void unsungMainAlphaInitializedNotSweptUsesLookAlpha() {
        Builder b = new Builder();
        b.lineLookInitialized = true;
        assertEquals(0.9f, LyricsLineAlphas.unsungMainAlpha(b.build(), false), DELTA);
    }

    @Test
    public void unsungMainAlphaUninitializedDimmedUsesUnsungAlpha() {
        assertEquals(0.35f * 0.5f,
                LyricsLineAlphas.unsungMainAlpha(new Builder().build(), true), DELTA);
    }

    @Test
    public void unsungMainAlphaUninitializedNotDimmedUsesBaseTextAlpha() {
        Builder b = new Builder();
        b.sweepEnabled = false;
        assertEquals((200 / 255f) * 0.5f,
                LyricsLineAlphas.unsungMainAlpha(b.build(), true), DELTA);
    }

    @Test
    public void translationAlphaInitializedUsesLookTransAlpha() {
        Builder b = new Builder();
        b.lineLookInitialized = true;
        assertEquals(0.7f, LyricsLineAlphas.translationAlpha(b.build()), DELTA);
    }

    @Test
    public void translationAlphaUninitializedActiveUsesActiveAlpha() {
        assertEquals(0.8f * 0.5f,
                LyricsLineAlphas.translationAlpha(new Builder().build()), DELTA);
    }

    @Test
    public void translationAlphaUninitializedInactiveUsesUnsungAlpha() {
        Builder b = new Builder();
        b.activeLine = false;
        assertEquals(0.35f * 0.5f, LyricsLineAlphas.translationAlpha(b.build()), DELTA);
    }

    @Test
    public void unsungReadingAlphaInitializedSweptUsesUnsungLookAlpha() {
        Builder b = new Builder();
        b.lineLookInitialized = true;
        assertEquals(0.4f, LyricsLineAlphas.unsungReadingAlpha(b.build(), true), DELTA);
    }

    @Test
    public void unsungReadingAlphaInitializedNotSweptUsesReadingLookAlpha() {
        Builder b = new Builder();
        b.lineLookInitialized = true;
        assertEquals(0.6f, LyricsLineAlphas.unsungReadingAlpha(b.build(), false), DELTA);
    }

    @Test
    public void unsungReadingAlphaInitializedSweptButWordSyncOffUsesReadingLookAlpha() {
        Builder b = new Builder();
        b.lineLookInitialized = true;
        b.wordSyncEnabled = false;
        assertEquals(0.6f, LyricsLineAlphas.unsungReadingAlpha(b.build(), true), DELTA);
    }

    @Test
    public void unsungReadingAlphaUninitializedInactiveUsesUnsungAlpha() {
        Builder b = new Builder();
        b.activeLine = false;
        assertEquals(0.35f * 0.5f, LyricsLineAlphas.unsungReadingAlpha(b.build(), true), DELTA);
    }

    @Test
    public void unsungReadingAlphaUninitializedActiveSweptUsesUnsungAlpha() {
        assertEquals(0.35f * 0.5f,
                LyricsLineAlphas.unsungReadingAlpha(new Builder().build(), true), DELTA);
    }

    @Test
    public void unsungReadingAlphaUninitializedActiveNotSweptUsesActiveReadingAlpha() {
        assertEquals(0.8f * 0.5f,
                LyricsLineAlphas.unsungReadingAlpha(new Builder().build(), false), DELTA);
    }
}

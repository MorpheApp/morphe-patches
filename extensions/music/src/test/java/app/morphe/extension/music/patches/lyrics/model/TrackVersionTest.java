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

public class TrackVersionTest {
    @Test public void unpairedPunctuationAtTheEdgesIsStripped() {
        assertEquals("Acoustic (Deluxe)", TrackVersion.clean("Acoustic (Deluxe),"));
        assertEquals("Remix", TrackVersion.clean("- Remix"));
        assertEquals("2026 Remaster", TrackVersion.clean("\"2026 Remaster\""));
        assertEquals("Acoustic", TrackVersion.clean("(Acoustic)"));
        assertEquals("Acoustic", TrackVersion.clean("\u201CAcoustic\u201D"));
    }

    @Test public void pairedPunctuationInsideTheVersionIsKept() {
        assertEquals("Acoustic (Deluxe)", TrackVersion.clean("Acoustic (Deluxe)"));
        assertEquals("\"Acoustic\" Version", TrackVersion.clean("\"Acoustic\" Version"));
    }

    @Test public void unicodePairsUseTheSharedDatabase() {
        assertEquals("🎵 Live", TrackVersion.clean("🎵 Live"));
        assertEquals("ライブ", TrackVersion.clean("「ライブ」"));
        assertEquals("版本（现场）", TrackVersion.clean("版本（现场），"));
        assertEquals("「ライブ」 版本", TrackVersion.clean("「ライブ」 版本"));
    }

    @Test public void variantQueryPreservesBackendIdentity() {
        TrackInfo cleaned = new TrackInfo("Duvet (Acoustic)", "boa", "The Race", 220);
        TrackInfo query = TrackVersion.query(cleaned, "Duvet (Acoustic)", "bôa");
        assertEquals("bôa", query.artist());
        assertEquals("Duvet (Acoustic)", query.title());
        assertEquals(cleaned.durationSeconds(), query.durationSeconds());
        assertEquals(cleaned.album(), query.album());
    }

    @Test public void missingRawMetadataFallsBackToTheLyricQuery() {
        TrackInfo cleaned = new TrackInfo("Rockstar (Olympia)", "Hole", "", 180);
        assertEquals(cleaned, TrackVersion.query(cleaned, null, ""));
    }

    @Test public void titlesMatchIgnoringCaseAndSpace() {
        assertEquals(TrackVersion.normalize("Duvet"), TrackVersion.normalize(" duvet "));
    }

    @Test public void combinedTitlesUseTheBackendFieldsWithoutInferringParentheses() {
        assertTrue(TrackVersion.matchesStructuredTitle("Duvet (Acoustic)", "Duvet - Acoustic",
                "Duvet", "(Acoustic)"));
        assertFalse(TrackVersion.matchesStructuredTitle("Duvet", "Duvet (Acoustic)",
                "Duvet", "(Acoustic)"));
        assertFalse(TrackVersion.matchesStructuredTitle("Rockstar (Olympia)", "Rockstar",
                "Rockstar", ""));
        assertTrue(TrackVersion.matchesStructuredTitle("Rockstar (Olympia)", "Rockstar (Olympia)",
                "Rockstar (Olympia)", ""));
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

public class DeezerVariantTest {
    @Test public void unpairedPunctuationAtTheEdgesIsStripped() {
        assertEquals("Acoustic (Deluxe)", DeezerProvider.cleanVersion("Acoustic (Deluxe),"));
        assertEquals("Remix", DeezerProvider.cleanVersion("- Remix"));
        assertEquals("2026 Remaster", DeezerProvider.cleanVersion("\"2026 Remaster\""));
        assertEquals("Acoustic", DeezerProvider.cleanVersion("(Acoustic)"));
        assertEquals("Acoustic", DeezerProvider.cleanVersion("\u201CAcoustic\u201D"));
    }

    @Test public void pairedPunctuationInsideTheVersionIsKept() {
        assertEquals("Acoustic (Deluxe)", DeezerProvider.cleanVersion("Acoustic (Deluxe)"));
        assertEquals("\"Acoustic\" Version", DeezerProvider.cleanVersion("\"Acoustic\" Version"));
    }

    @Test public void unicodePairsUseTheSharedDatabase() {
        assertEquals("🎵 Live", DeezerProvider.cleanVersion("🎵 Live"));
        assertEquals("ライブ", DeezerProvider.cleanVersion("「ライブ」"));
        assertEquals("版本（现场）", DeezerProvider.cleanVersion("版本（现场），"));
        assertEquals("「ライブ」 版本", DeezerProvider.cleanVersion("「ライブ」 版本"));
    }

    @Test public void variantQueryPreservesBackendIdentity() {
        TrackInfo cleaned = new TrackInfo("Duvet (Acoustic)", "boa", "The Race", 220);
        TrackInfo query = DeezerProvider.versionQuery(cleaned, "Duvet (Acoustic)", "bôa");
        assertEquals("bôa", query.artist());
        assertEquals("Duvet (Acoustic)", query.title());
        assertEquals(cleaned.durationSeconds(), query.durationSeconds());
        assertEquals(cleaned.album(), query.album());
    }

    @Test public void missingRawMetadataFallsBackToTheLyricQuery() {
        TrackInfo cleaned = new TrackInfo("Rockstar (Olympia)", "Hole", "", 180);
        assertEquals(cleaned, DeezerProvider.versionQuery(cleaned, null, ""));
    }

    @Test public void titlesMatchIgnoringCaseAndSpace() {
        assertEquals(DeezerProvider.normalize("Duvet"), DeezerProvider.normalize(" duvet "));
    }
    @Test public void combinedTitlesUseTheBackendFieldsWithoutInferringParentheses() {
        assertTrue(DeezerProvider.matchesStructuredTitle("Duvet (Acoustic)", "Duvet - Acoustic",
                "Duvet", "(Acoustic)"));
        assertFalse(DeezerProvider.matchesStructuredTitle("Duvet", "Duvet (Acoustic)",
                "Duvet", "(Acoustic)"));
        assertFalse(DeezerProvider.matchesStructuredTitle("Rockstar (Olympia)", "Rockstar",
                "Rockstar", ""));
        assertTrue(DeezerProvider.matchesStructuredTitle("Rockstar (Olympia)", "Rockstar (Olympia)",
                "Rockstar (Olympia)", ""));
    }

}

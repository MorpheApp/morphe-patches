/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ArtistNameNormalizerTest {

    @Test public void lowercasesAndReplacesSpacesWithDashes() {
        assertEquals("daft-punk", ArtistNameNormalizer.toArtistId("Daft Punk"));
        assertEquals("1950-soulclub", ArtistNameNormalizer.toArtistId("1950 SoulClub"));
    }

    @Test public void keepsPunctuationLikeTheDatabaseIds() {
        assertEquals("*nsync", ArtistNameNormalizer.toArtistId("*NSYNC"));
        assertEquals("ash-&-chalice", ArtistNameNormalizer.toArtistId("Ash & Chalice"));
        assertEquals("beat-saber-(original-game-soundtrack),-vol.viii",
                ArtistNameNormalizer.toArtistId("Beat Saber (Original Game Soundtrack), Vol.VIII"));
    }

    @Test public void trimsAndCollapsesWhitespace() {
        assertEquals("tame-impala", ArtistNameNormalizer.toArtistId("  Tame   Impala \t"));
    }

    @Test public void removesTheTopicChannelSuffix() {
        assertEquals("daft-punk", ArtistNameNormalizer.toArtistId("Daft Punk - Topic"));
        assertEquals("daft-punk", ArtistNameNormalizer.toArtistId("Daft Punk -topic"));
    }

    @Test public void displayNameKeepsTheCase() {
        assertEquals("Daft Punk", ArtistNameNormalizer.toDisplayName("  Daft   Punk - Topic"));
    }

    @Test public void blankNameGivesEmptyId() {
        assertEquals("", ArtistNameNormalizer.toArtistId("   "));
    }
}

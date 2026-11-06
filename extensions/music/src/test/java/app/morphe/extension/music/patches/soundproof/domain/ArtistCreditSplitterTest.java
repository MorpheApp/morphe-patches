/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class ArtistCreditSplitterTest {

    @Test public void singleArtistIsKeptAsIs() {
        assertEquals(List.of("Daft Punk"), ArtistCreditSplitter.split("Daft Punk"));
    }

    @Test public void splitsOnCommasAndAmpersands() {
        assertEquals(List.of("A", "B", "C"), ArtistCreditSplitter.split("A, B & C"));
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A&B"));
    }

    @Test public void splitsOnFeaturingKeywordsIgnoringCase() {
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A feat. B"));
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A FT B"));
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A featuring B"));
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A x B"));
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A × B"));
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split("A vs. B"));
    }

    @Test public void keywordsInsideAWordAreNotSeparators() {
        assertEquals(List.of("Xenia"), ArtistCreditSplitter.split("Xenia"));
        assertEquals(List.of("Maxx Feather"), ArtistCreditSplitter.split("Maxx Feather"));
    }

    @Test public void bandNamesWithPlusOrAndAreNotSplit() {
        assertEquals(List.of("Florence + the Machine"), ArtistCreditSplitter.split("Florence + the Machine"));
        assertEquals(List.of("Simon and Garfunkel"), ArtistCreditSplitter.split("Simon and Garfunkel"));
    }

    @Test public void blankPartsAreDropped() {
        assertEquals(List.of("A", "B"), ArtistCreditSplitter.split(" , A,, B , "));
    }
}

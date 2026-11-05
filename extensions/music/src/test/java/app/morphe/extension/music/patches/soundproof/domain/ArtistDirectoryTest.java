/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.List;

public class ArtistDirectoryTest {

    private static final ArtistRecord HONEY = new ArtistRecord("honey", "Honey", 5, 0, false);
    private static final ArtistRecord TEN_MM = new ArtistRecord("10mm", "10mm", 0, 1000, true);

    private final ArtistDirectory directory = new ArtistDirectory(List.of(HONEY, TEN_MM));

    @Test public void replacementWinsOverTheEntryWithTheSameId() {
        ArtistRecord freshHoney = new ArtistRecord("honey", "Honey", 5, 9, false);
        ArtistDirectory merged = directory.withArtists(List.of(freshHoney));
        assertEquals(freshHoney, merged.findByName("Honey").orElseThrow());
        assertEquals(TEN_MM, merged.findByName("10mm").orElseThrow());
        assertEquals(HONEY, directory.findByName("Honey").orElseThrow());
    }

    @Test public void unlistedReplacementIsAdded() {
        ArtistRecord newcomer = new ArtistRecord("new-artist", "New Artist", 0, 1, false);
        ArtistDirectory merged = directory.withArtists(List.of(newcomer));
        assertEquals(3, merged.size());
        assertEquals(newcomer, merged.findByName("New Artist").orElseThrow());
    }

    @Test public void laterReplacementWinsAndCountsOnce() {
        ArtistRecord freshHoney = new ArtistRecord("honey", "Honey", 5, 9, false);
        ArtistRecord fresherHoney = new ArtistRecord("honey", "Honey", 5, 10, false);
        ArtistDirectory merged = directory.withArtists(List.of(freshHoney)).withArtists(List.of(fresherHoney));
        assertEquals(fresherHoney, merged.findByName("Honey").orElseThrow());
        assertEquals(2, merged.size());
    }

    @Test public void noReplacementKeepsTheSameDirectory() {
        assertSame(directory, directory.withArtists(List.of()));
    }
}

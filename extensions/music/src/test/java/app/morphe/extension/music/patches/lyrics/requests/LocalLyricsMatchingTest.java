/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4947
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;

/**
 * MediaStore fallback matching. The title query can return other songs, so a row is only used
 * if its title and artist identify the playing track.
 */
public class LocalLyricsMatchingTest {

    @BeforeClass
    public static void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static boolean matches(String title, String artist,
                                   String storedTitle, String storedArtist) {
        return LocalLyricsFetcher.isSameTrack(title, null, artist, null, storedTitle, storedArtist);
    }

    @Test
    public void titleContainingThePlayingTitleDoesNotMatch() {
        assertFalse(matches("Rain", "Blue Harbor", "Before The Rain", "Grey Valley, Red Coast"));
        assertFalse(matches("Rain", "Blue Harbor", "Before The Rain", "Blue Harbor"));
    }

    @Test
    public void sameTitleByAnotherArtistDoesNotMatch() {
        assertFalse(matches("Rain", "Blue Harbor", "Rain", "Grey Valley"));
    }

    @Test
    public void artistContainingThePlayingArtistDoesNotMatch() {
        assertFalse(matches("Rain", "Blue Harbor", "Rain", "Blue Harbor Tribute Band"));
    }

    @Test
    public void missingMetadataDoesNotMatch() {
        assertFalse(matches("Rain", "Blue Harbor", "Rain", null));
        assertFalse(matches("Rain", "", "Rain", "Blue Harbor"));
        assertFalse(matches("Rain", "Blue Harbor", null, "Blue Harbor"));
    }

    @Test
    public void sameTitleAndArtistMatch() {
        assertTrue(matches("Rain", "Blue Harbor", "Rain", "Blue Harbor"));
    }

    @Test
    public void caseAndWhitespaceAreIgnored() {
        assertTrue(matches(" Rain ", "BLUE HARBOR", "rain", "Blue  Harbor"));
    }

    @Test
    public void rawTitleMatches() {
        assertTrue(LocalLyricsFetcher.isSameTrack("Rain", "Rain (Live)", "Blue Harbor", null,
                "Rain (Live)", "Blue Harbor"));
    }

    @Test
    public void storedCollaborationCreditingThePrimaryArtistMatches() {
        String rawArtist = "Grey Valley, Red Coast & Blue Harbor";
        // Same cleanup the lyrics lookup applies to the playing track.
        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist("Rain", rawArtist);
        assertEquals("Grey Valley", parsed[0]);

        assertTrue(LocalLyricsFetcher.isSameTrack(parsed[1], "Rain", parsed[0], rawArtist,
                "Rain", rawArtist));
        assertTrue(LocalLyricsFetcher.isSameTrack(parsed[1], "Rain", parsed[0], rawArtist,
                "Rain", "Grey Valley feat. Someone Else"));
        assertFalse(LocalLyricsFetcher.isSameTrack(parsed[1], "Rain", parsed[0], rawArtist,
                "Rain", "Red Coast"));
    }
}

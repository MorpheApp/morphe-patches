/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class MetadataCleanerTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    // ── cleanArtist ──

    @Test
    public void cleanArtistNullReturnsEmpty() {
        assertEquals("", MetadataCleaner.cleanArtist(null));
    }

    @Test
    public void cleanArtistSingleArtistUnchanged() {
        assertEquals("Artist", MetadataCleaner.cleanArtist("Artist"));
    }

    @Test
    public void cleanArtistMultiArtistTakesFirst() {
        assertEquals("Artist1", MetadataCleaner.cleanArtist("Artist1, Artist2"));
        assertEquals("Artist1", MetadataCleaner.cleanArtist("Artist1 & Artist2"));
        assertEquals("Artist1", MetadataCleaner.cleanArtist("Artist1 feat. Artist2"));
    }

    @Test
    public void cleanArtistWhitespaceCollapsed() {
        assertEquals("Artist", MetadataCleaner.cleanArtist("  Artist  "));
    }

    @Test
    public void cleanArtistFeaturingSeparator() {
        assertEquals("MainArtist", MetadataCleaner.cleanArtist("MainArtist ft. OtherArtist"));
    }

    // ── cleanAlbum ──

    @Test
    public void cleanAlbumNullReturnsEmpty() {
        assertEquals("", MetadataCleaner.cleanAlbum(null));
    }

    @Test
    public void cleanAlbumWhitespaceCollapsed() {
        assertEquals("Album Name", MetadataCleaner.cleanAlbum("  Album   Name  "));
    }

    // ── cleanTitle ──

    @Test
    public void cleanTitleWhitespaceCollapsed() {
        assertEquals("Song Title", MetadataCleaner.cleanTitle("  Song   Title  "));
    }

    // ── splitArtists ──

    @Test
    public void splitArtistsSingleArtist() {
        String[] parts = MetadataCleaner.splitArtists("Artist");
        assertEquals(1, parts.length);
        assertEquals("Artist", parts[0]);
    }

    @Test
    public void splitArtistsMultipleSeparators() {
        String[] parts = MetadataCleaner.splitArtists("Artist1 和 Artist2 & Artist3");
        assertEquals(3, parts.length);
        assertEquals("Artist1", parts[0]);
        assertEquals("Artist2", parts[1]);
        assertEquals("Artist3", parts[2]);
    }

    @Test
    public void splitArtistsCommaSeparator() {
        String[] parts = MetadataCleaner.splitArtists("Artist1, Artist2, Artist3");
        assertEquals(3, parts.length);
    }

    @Test
    public void splitArtistsEmptyPartsFiltered() {
        String[] parts = MetadataCleaner.splitArtists("Artist1 & & Artist2");
        assertEquals(2, parts.length);
    }

    // ── parseCleanTitleAndArtist ──

    @Test
    public void parseCleanTitleAndArtist() {
        String[] result = MetadataCleaner.parseCleanTitleAndArtist("Song Title", "Artist Name");
        assertEquals("Artist Name", result[0]);
        assertEquals("Song Title", result[1]);
    }

    // ── trustedDashSplit ──

    @Test
    public void trustedDashSplitArtistOnLeft() {
        TrackInfo result = MetadataCleaner.trustedDashSplit(
                "Artist - Song", "Artist", null, 180);
        assertNotNull(result);
        assertEquals("Song", result.title());
        assertEquals("Artist", result.artist());
        assertEquals(180, result.durationSeconds());
    }

    @Test
    public void trustedDashSplitArtistOnRight() {
        TrackInfo result = MetadataCleaner.trustedDashSplit(
                "Song - Artist", "Artist", null, 180);
        assertNotNull(result);
        assertEquals("Song", result.title());
        assertEquals("Artist", result.artist());
    }

    @Test
    public void trustedDashSplitNoDashReturnsNull() {
        assertNull(MetadataCleaner.trustedDashSplit("NoDashHere", "Artist", null, 0));
    }

    @Test
    public void trustedDashSplitArtistNotInTitleReturnsNull() {
        assertNull(MetadataCleaner.trustedDashSplit(
                "Song - SomeoneElse", "Artist", null, 0));
    }

    @Test
    public void trustedDashSplitNullArtistReturnsNull() {
        assertNull(MetadataCleaner.trustedDashSplit("Artist - Song", null, null, 0));
    }

    // ── anyDashSplit ──

    @Test
    public void anyDashSplitBasic() {
        TrackInfo result = MetadataCleaner.anyDashSplit("Artist - Song", null, 180);
        assertNotNull(result);
        assertEquals("Song", result.title());
        assertEquals("Artist", result.artist());
    }

    @Test
    public void anyDashSplitNoDashReturnsNull() {
        assertNull(MetadataCleaner.anyDashSplit("NoDashHere", null, 0));
    }

    @Test
    public void anyDashSplitEmptySideReturnsNull() {
        assertNull(MetadataCleaner.anyDashSplit(" - Song", null, 0));
        assertNull(MetadataCleaner.anyDashSplit("Artist - ", null, 0));
    }

    // ── anyDashSplitReversed ──

    @Test
    public void anyDashSplitReversedBasic() {
        TrackInfo result = MetadataCleaner.anyDashSplitReversed("Song - Artist", null, 180);
        assertNotNull(result);
        assertEquals("Song", result.title());
        assertEquals("Artist", result.artist());
    }

    // ── resolveSetting ──

    @Test
    public void resolveSettingNullReturnsEmpty() {
        assertEquals("", MetadataCleaner.resolveSetting(null));
    }

    @Test
    public void resolveSettingBlankReturnsBlank() {
        assertEquals("   ", MetadataCleaner.resolveSetting("   "));
    }

    @Test
    public void resolveSettingLocalValueReturned() {
        assertEquals("local-value", MetadataCleaner.resolveSetting("local-value"));
    }

    // ── applyRegex ──

    @Test
    public void applyRegexEmptyRegexReturnsInput() {
        assertEquals("hello world", MetadataCleaner.applyRegex("hello world", ""));
        assertEquals("hello world", MetadataCleaner.applyRegex("hello world", null));
    }

    @Test
    public void applyRegexRemovesMatches() {
        String result = MetadataCleaner.applyRegex("hello [world]", "\\[.*?\\]");
        assertEquals("hello ", result);
    }

    @Test
    public void applyRegexInvalidPatternReturnsInput() {
        assertEquals("hello", MetadataCleaner.applyRegex("hello", "[invalid"));
    }

    // ── applyRegexPreserveOriginal ──

    @Test
    public void applyRegexPreserveOriginalEmptyRegexReturnsInput() {
        assertEquals("hello", MetadataCleaner.applyRegexPreserveOriginal("hello", ""));
    }

    @Test
    public void applyRegexPreserveOriginalNoMatchReturnsOriginal() {
        String input = "hello，world！"; // fullwidth punctuation
        String result = MetadataCleaner.applyRegexPreserveOriginal(input, "\\[.*?\\]");
        assertEquals(input, result);
    }

    @Test
    public void applyRegexPreserveOriginalMatchReturnsFiltered() {
        String result = MetadataCleaner.applyRegexPreserveOriginal("hello [world]", "\\[.*?\\]");
        assertEquals("hello ", result);
    }
}

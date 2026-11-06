/* Copyright 2026 Morphe. See the included NOTICE file for GPLv3 Section 7 terms that apply to this code. */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.settings.Settings;

/** Unit tests for {@link LyricsMatch}: validation, cache-source mapping, fingerprinting, provider classification and SponsorBlock duration correction. {@link LyricsTestEnv#bootstrap()} runs in {@code @Before} so {@link Settings} loads under the fake context. */
public class LyricsMatchTest {

    private TrackInfo track;

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        // Reset to a known state; default is TRUE, so save(false) always changes it on first run.
        Settings.LYRICS_SB_MATCHING.save(false);
        track = LyricsFixtures.track("Test Title", "Test Artist", "Test Album", 120);
    }

    // ---- helpers ----

    private static Lyrics lyrics(String provider, LyricsLine... lines) {
        return new Lyrics(List.of(lines), provider, true,
                null, null, null, null, null, null, null);
    }

    private static Lyrics lyricsWithRaw(String provider, String rawFormat, LyricsLine... lines) {
        return new Lyrics(List.of(lines), provider, true,
                null, null, null, null, rawFormat, "lrc", null);
    }

    private static LyricsProvider provider(String name) {
        return new LyricsProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public LyricsProvider.FetchResult fetch(TrackInfo track) {
                return null;
            }
        };
    }

    // ---- isValidLyrics ----

    @Test
    public void isValidLyrics_nullReturnsFalse() {
        assertFalse(LyricsMatch.isValidLyrics(null, track));
    }

    @Test
    public void isValidLyrics_notFoundReturnsFalse() {
        assertFalse(LyricsMatch.isValidLyrics(Lyrics.NOT_FOUND, track));
    }

    @Test
    public void isValidLyrics_emptyReturnsFalse() {
        Lyrics empty = new Lyrics(List.of(), "TestProvider", false,
                null, null, null, null, null, null, null);
        assertFalse(LyricsMatch.isValidLyrics(empty, track));
    }

    @Test
    public void isValidLyrics_validWithCreditLinesReturnsTrue() {
        Lyrics lyrics = lyrics("TestProvider",
                LyricsFixtures.line(0, "Test Artist - Test Title"),
                LyricsFixtures.line(1_000, "Hello world"),
                LyricsFixtures.line(2_000, "Another line"));
        assertTrue(LyricsMatch.isValidLyrics(lyrics, track));
    }

    @Test
    public void isValidLyrics_validNoCreditsReturnsTrue() {
        Lyrics lyrics = lyrics("TestProvider",
                LyricsFixtures.line(0, "Hello world"),
                LyricsFixtures.line(1_000, "Another line"));
        assertTrue(LyricsMatch.isValidLyrics(lyrics, track));
    }

    @Test
    public void isValidLyrics_onlyCreditLinesReturnsFalse() {
        Lyrics lyrics = lyrics("TestProvider",
                LyricsFixtures.line(0, "Test Artist - Test Title"));
        assertFalse(LyricsMatch.isValidLyrics(lyrics, track));
    }

    // ---- cacheSourceFor ----

    @Test
    public void cacheSourceFor_knownProviderReturnsId() {
        Lyrics lyrics = lyrics("Apple", LyricsFixtures.line(0, "Hello"));
        assertEquals("Apple", LyricsMatch.cacheSourceFor(lyrics));
    }

    @Test
    public void cacheSourceFor_knownProviderWithQualifierReturnsId() {
        Lyrics lyrics = lyrics("Apple (via Lyrically)", LyricsFixtures.line(0, "Hello"));
        assertEquals("Apple", LyricsMatch.cacheSourceFor(lyrics));
    }

    @Test
    public void cacheSourceFor_unknownProviderReturnsRawName() {
        Lyrics lyrics = lyrics("MyCustomProvider", LyricsFixtures.line(0, "Hello"));
        assertEquals("MyCustomProvider", LyricsMatch.cacheSourceFor(lyrics));
    }

    @Test
    public void cacheSourceFor_nullReturnsEmpty() {
        assertEquals("", LyricsMatch.cacheSourceFor(null));
    }

    // ---- fingerprint ----

    @Test
    public void fingerprint_withRawFormatUsesRawHash() {
        String raw = "[00:00]Hello\n[00:10]World";
        Lyrics lyrics = lyricsWithRaw("Apple", raw,
                LyricsFixtures.line(0, "Hello"),
                LyricsFixtures.line(10_000, "World"));
        String expected = "Apple|" + raw.hashCode();
        assertEquals(expected, LyricsMatch.fingerprint(lyrics));
    }

    @Test
    public void fingerprint_withoutRawFormatUsesLinesTextHash() {
        Lyrics lyrics = lyrics("Apple",
                LyricsFixtures.line(0, "Hello"),
                LyricsFixtures.line(10_000, "World"));
        String concatenated = "Hello" + "World";
        String expected = "Apple|" + concatenated.hashCode();
        assertEquals(expected, LyricsMatch.fingerprint(lyrics));
    }

    @Test
    public void fingerprint_emptyRawFormatFallsBackToLinesText() {
        Lyrics lyrics = lyricsWithRaw("Apple", "",
                LyricsFixtures.line(0, "Hello"));
        String expected = "Apple|" + "Hello".hashCode();
        assertEquals(expected, LyricsMatch.fingerprint(lyrics));
    }

    @Test
    public void fingerprint_nullReturnsEmpty() {
        assertEquals("", LyricsMatch.fingerprint(null));
    }

    @Test
    public void fingerprint_differentRawFormatsProduceDifferentFingerprints() {
        Lyrics withRaw1 = lyricsWithRaw("Apple", "raw-text-one",
                LyricsFixtures.line(0, "Same"));
        Lyrics withRaw2 = lyricsWithRaw("Apple", "raw-text-two",
                LyricsFixtures.line(0, "Same"));
        assertNotEquals(LyricsMatch.fingerprint(withRaw1), LyricsMatch.fingerprint(withRaw2));
    }

    // ---- isVideoIdMatchedLyrics ----

    @Test
    public void isVideoIdMatchedLyrics_captionsReturnsTrue() {
        Lyrics captions = lyrics("Captions", LyricsFixtures.line(0, "Hello"));
        assertTrue(LyricsMatch.isVideoIdMatchedLyrics(captions));
    }

    @Test
    public void isVideoIdMatchedLyrics_captionsWithViaReturnsTrue() {
        Lyrics captions = lyrics("Captions (via YouTube)", LyricsFixtures.line(0, "Hello"));
        assertTrue(LyricsMatch.isVideoIdMatchedLyrics(captions));
    }

    @Test
    public void isVideoIdMatchedLyrics_unisonReturnsTrue() {
        Lyrics unison = lyrics("Unison", LyricsFixtures.line(0, "Hello"));
        assertTrue(LyricsMatch.isVideoIdMatchedLyrics(unison));
    }

    @Test
    public void isVideoIdMatchedLyrics_simpMusicReturnsTrue() {
        Lyrics simp = lyrics("SimpMusic", LyricsFixtures.line(0, "Hello"));
        assertTrue(LyricsMatch.isVideoIdMatchedLyrics(simp));
    }

    @Test
    public void isVideoIdMatchedLyrics_youtubeMusicReturnsTrue() {
        Lyrics ytm = lyrics("YouTube Music", LyricsFixtures.line(0, "Hello"));
        assertTrue(LyricsMatch.isVideoIdMatchedLyrics(ytm));
    }

    @Test
    public void isVideoIdMatchedLyrics_youtubeMusicWithQualifierReturnsTrue() {
        Lyrics ytm = lyrics("YouTube Music (Premium)", LyricsFixtures.line(0, "Hello"));
        assertTrue(LyricsMatch.isVideoIdMatchedLyrics(ytm));
    }

    @Test
    public void isVideoIdMatchedLyrics_regularProviderReturnsFalse() {
        Lyrics genius = lyrics("Genius", LyricsFixtures.line(0, "Hello"));
        assertFalse(LyricsMatch.isVideoIdMatchedLyrics(genius));
    }

    @Test
    public void isVideoIdMatchedLyrics_nullReturnsFalse() {
        assertFalse(LyricsMatch.isVideoIdMatchedLyrics(null));
    }

    // ---- isBlindProvider ----

    @Test
    public void isBlindProvider_spotifyReturnsTrue() {
        assertTrue(LyricsMatch.isBlindProvider(provider("Spotify")));
    }

    @Test
    public void isBlindProvider_amllReturnsTrue() {
        assertTrue(LyricsMatch.isBlindProvider(provider("AMLL")));
    }

    @Test
    public void isBlindProvider_blyricsReturnsTrue() {
        assertTrue(LyricsMatch.isBlindProvider(provider("bLyrics")));
    }

    @Test
    public void isBlindProvider_binilyricsReturnsTrue() {
        assertTrue(LyricsMatch.isBlindProvider(provider("BiniLyrics")));
    }

    @Test
    public void isBlindProvider_lyricifyReturnsTrue() {
        assertTrue(LyricsMatch.isBlindProvider(provider("Lyricify")));
    }

    @Test
    public void isBlindProvider_regularProviderReturnsFalse() {
        assertFalse(LyricsMatch.isBlindProvider(provider("Apple")));
    }

    @Test
    public void isBlindProvider_geniusReturnsFalse() {
        assertFalse(LyricsMatch.isBlindProvider(provider("Genius")));
    }

    // ---- withEffectiveDuration ----

    @Test
    public void withEffectiveDuration_settingOffReturnsSameTrack() {
        Settings.LYRICS_SB_MATCHING.save(false);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(10_000, 20_000, "sponsor", 0));
        TrackInfo result = LyricsMatch.withEffectiveDuration(track, segs);
        assertSame(track, result);
    }

    @Test
    public void withEffectiveDuration_settingOnWithSegmentsReducesDuration() {
        Settings.LYRICS_SB_MATCHING.save(true);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(10_000, 20_000, "sponsor", 0));
        TrackInfo result = LyricsMatch.withEffectiveDuration(track, segs);
        assertEquals(110, result.durationSeconds());
        assertEquals("Test Title", result.title());
        assertEquals("Test Artist", result.artist());
        assertEquals("Test Album", result.album());
    }

    @Test
    public void withEffectiveDuration_settingOnNoSegmentsReturnsSameTrack() {
        Settings.LYRICS_SB_MATCHING.save(true);
        assertSame(track, LyricsMatch.withEffectiveDuration(track, null));
        assertSame(track, LyricsMatch.withEffectiveDuration(track, List.of()));
    }

    @Test
    public void withEffectiveDuration_settingOnZeroDurationTrackReturnsSameTrack() {
        Settings.LYRICS_SB_MATCHING.save(true);
        TrackInfo zeroDuration = new TrackInfo("Title", "Artist", "Album", 0);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(0, 1_000, "sponsor", 0));
        assertSame(zeroDuration, LyricsMatch.withEffectiveDuration(zeroDuration, segs));
    }

    @Test
    public void withEffectiveDuration_settingOnSegmentsCoveringAllReturnsSameTrack() {
        Settings.LYRICS_SB_MATCHING.save(true);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(0, 120_000, "sponsor", 0));
        TrackInfo result = LyricsMatch.withEffectiveDuration(track, segs);
        assertSame(track, result);
    }

    @Test
    public void withEffectiveDuration_settingOnMultipleSegmentsReducesDuration() {
        Settings.LYRICS_SB_MATCHING.save(true);
        List<SkipSegments.Seg> segs = List.of(
                new SkipSegments.Seg(5_000, 15_000, "sponsor", 0),
                new SkipSegments.Seg(20_000, 40_000, "sponsor", 0));
        TrackInfo result = LyricsMatch.withEffectiveDuration(track, segs);
        assertEquals(90, result.durationSeconds());
    }

    @Test
    public void withEffectiveDuration_settingOnPreservesTitleArtistAlbum() {
        Settings.LYRICS_SB_MATCHING.save(true);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(10_000, 20_000, "sponsor", 0));
        TrackInfo result = LyricsMatch.withEffectiveDuration(track, segs);
        assertEquals(track.title(), result.title());
        assertEquals(track.artist(), result.artist());
        assertEquals(track.album(), result.album());
    }
}

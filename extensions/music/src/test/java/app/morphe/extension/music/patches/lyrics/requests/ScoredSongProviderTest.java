/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider.FetchResult;
import app.morphe.extension.music.patches.lyrics.requests.ScoredSongProvider.ScoredSong;

/**
 * Unit tests for {@link ScoredSongProvider}: fetch, fetchCandidates, hasCandidates,
 * and the ScoredSong record fields.
 */
public class ScoredSongProviderTest {

    /** A test subclass that returns a preset list of scored songs. */
    private static class TestScoredSongProvider extends ScoredSongProvider {
        private final List<ScoredSong> songs;

        TestScoredSongProvider(List<ScoredSong> songs) {
            this.songs = songs;
        }

        @Override
        protected List<ScoredSong> fetchScoredSongs(TrackInfo track) {
            return songs;
        }

        @Override
        public String name() {
            return "TestProvider";
        }
    }

    private static TrackInfo track() {
        return new TrackInfo("Hello", "World", "Album", 180);
    }

    private static Lyrics sampleLyrics(String text) {
        return LyricsFixtures.synced(LyricsFixtures.line(1000, text));
    }

    // ── fetch ──

    @Test
    public void fetchWithNonEmptyCandidatesReturnsFetchResult() throws Exception {
        Lyrics lyrics = sampleLyrics("Hello");
        ScoredSong song = new ScoredSong(
                8, lyrics, "Hello", "World", 180);
        TestScoredSongProvider provider = new TestScoredSongProvider(List.of(song));

        FetchResult result = provider.fetch(track());
        assertNotNull(result);
        assertNotNull(result.lyrics());
        assertEquals("Hello", result.sourceTitle());
        assertEquals("World", result.sourceArtist());
        assertEquals(180L, result.sourceDurationSec());
    }

    @Test
    public void fetchWithEmptyCandidatesReturnsNull() throws Exception {
        TestScoredSongProvider provider = new TestScoredSongProvider(List.of());
        assertNull(provider.fetch(track()));
    }

    @Test
    public void fetchUsesTopCandidate() throws Exception {
        Lyrics first = sampleLyrics("First");
        Lyrics second = sampleLyrics("Second");
        List<ScoredSong> songs = List.of(
                new ScoredSong(10, first, "First", "ArtistA", 180),
                new ScoredSong(5, second, "Second", "ArtistB", 200));
        TestScoredSongProvider provider = new TestScoredSongProvider(songs);

        FetchResult result = provider.fetch(track());
        assertNotNull(result);
        assertEquals("First", result.sourceTitle());
        assertEquals("ArtistA", result.sourceArtist());
        assertEquals(180L, result.sourceDurationSec());
    }

    @Test
    public void fetchWithNullLyricsInTopCandidate() throws Exception {
        ScoredSong song = new ScoredSong(
                8, null, "Hello", "World", 180);
        TestScoredSongProvider provider = new TestScoredSongProvider(List.of(song));

        // FetchResult.of(null, ...) returns null
        assertNull(provider.fetch(track()));
    }

    // ── fetchCandidates ──

    @Test
    public void fetchCandidatesReturnsScoredLyricsList() throws Exception {
        Lyrics first = sampleLyrics("First");
        Lyrics second = sampleLyrics("Second");
        List<ScoredSong> songs = List.of(
                new ScoredSong(8, first, "First", "A", 180),
                new ScoredSong(5, second, "Second", "B", 200));
        TestScoredSongProvider provider = new TestScoredSongProvider(songs);

        List<Lyrics.ScoredLyrics> candidates = provider.fetchCandidates(track());
        assertEquals(2, candidates.size());
        assertEquals(8, candidates.get(0).score());
        assertEquals(first, candidates.get(0).lyrics());
        assertEquals(5, candidates.get(1).score());
        assertEquals(second, candidates.get(1).lyrics());
    }

    @Test
    public void fetchCandidatesEmptyWhenNoSongs() throws Exception {
        TestScoredSongProvider provider = new TestScoredSongProvider(List.of());
        List<Lyrics.ScoredLyrics> candidates = provider.fetchCandidates(track());
        assertTrue(candidates.isEmpty());
    }

    // ── hasCandidates ──

    @Test
    public void hasCandidatesAlwaysTrue() {
        TestScoredSongProvider emptyProvider = new TestScoredSongProvider(List.of());
        assertTrue(emptyProvider.hasCandidates());

        Lyrics lyrics = sampleLyrics("Hello");
        TestScoredSongProvider nonEmptyProvider = new TestScoredSongProvider(List.of(
                new ScoredSong(8, lyrics, "Hello", "World", 180)));
        assertTrue(nonEmptyProvider.hasCandidates());
    }

    // ── ScoredSong record fields ──

    @Test
    public void scoredSongRecordFields() {
        Lyrics lyrics = sampleLyrics("Hello");
        ScoredSong song = new ScoredSong(
                8, lyrics, "Hello", "World", 180);
        assertEquals(8, song.score());
        assertEquals(lyrics, song.lyrics());
        assertEquals("Hello", song.title());
        assertEquals("World", song.artist());
        assertEquals(180L, song.durationSec());
    }

    @Test
    public void scoredSongDurationZeroWhenUnknown() {
        Lyrics lyrics = sampleLyrics("Hello");
        ScoredSong song = new ScoredSong(
                5, lyrics, "Hello", "World", 0);
        assertEquals(0L, song.durationSec());
    }

    @Test
    public void scoredSongWithNullTitleAndArtist() {
        Lyrics lyrics = sampleLyrics("Hello");
        ScoredSong song = new ScoredSong(
                5, lyrics, null, null, 0);
        assertEquals(5, song.score());
        assertEquals(lyrics, song.lyrics());
        assertNull(song.title());
        assertNull(song.artist());
        assertEquals(0L, song.durationSec());
    }

    // ── fetch preserves queried variant ──

    @Test
    public void fetchPassesTrackAsQueriedVariant() throws Exception {
        Lyrics lyrics = sampleLyrics("Hello");
        ScoredSong song = new ScoredSong(
                8, lyrics, "Hello", "World", 180);
        TestScoredSongProvider provider = new TestScoredSongProvider(List.of(song));

        TrackInfo track = track();
        FetchResult result = provider.fetch(track);
        assertNotNull(result);
        assertEquals(track, result.queriedVariant());
    }

    // ── multiple candidates ordering ──

    @Test
    public void fetchCandidatesPreservesOrder() throws Exception {
        Lyrics first = sampleLyrics("First");
        Lyrics second = sampleLyrics("Second");
        Lyrics third = sampleLyrics("Third");
        List<ScoredSong> songs = List.of(
                new ScoredSong(10, first, "First", "A", 180),
                new ScoredSong(7, second, "Second", "B", 200),
                new ScoredSong(3, third, "Third", "C", 220));
        TestScoredSongProvider provider = new TestScoredSongProvider(songs);

        List<Lyrics.ScoredLyrics> candidates = provider.fetchCandidates(track());
        assertEquals(3, candidates.size());
        assertEquals(10, candidates.get(0).score());
        assertEquals(7, candidates.get(1).score());
        assertEquals(3, candidates.get(2).score());
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;

@SuppressWarnings("unused")
public class LyricsCacheTest {

    private TrackInfo track;

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        track = LyricsFixtures.track("Test Song", "Test Artist");
    }

    // ── get / put round trip ──

    @Test
    public void getReturnsNullWhenNotFound() {
        assertNull(LyricsCache.get(track, "source", null));
    }

    @Test
    public void putThenGetReturnsSameLyrics() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello world"));
        LyricsCache.put(track, "TestProvider", lyrics, null);
        Lyrics result = LyricsCache.get(track, "TestProvider", null);
        assertNotNull(result);
        assertEquals(1, result.lines().size());
        assertEquals("hello world", result.lines().get(0).text());
    }

    @Test
    public void writersAndCreditsSurviveTheCache() {
        Lyrics lyrics = new Lyrics(List.of(new LyricsLine(0, "hello")), "TestProvider", true,
                null, null, null, List.of("John Doe"), List.of("Uploaded by Jane"),
                null, null, null, null, null);
        LyricsCache.put(track, "TestProvider", lyrics, null);
        Lyrics result = LyricsCache.get(track, "TestProvider", null);
        assertNotNull(result);
        assertEquals(List.of("John Doe"), result.songwriters());
        assertEquals(List.of("Uploaded by Jane"), result.credits());
    }

    @Test
    public void getWithVideoIdMatches() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello"));
        LyricsCache.put(track, "TestProvider", lyrics, "video123");
        Lyrics result = LyricsCache.get(track, "TestProvider", "video123");
        assertNotNull(result);
    }

    @Test
    public void getWithDifferentVideoIdReturnsNull() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello"));
        LyricsCache.put(track, "TestProvider", lyrics, "video123");
        Lyrics result = LyricsCache.get(track, "TestProvider", "different");
        assertNull(result);
    }

    @Test
    public void getWithEmptyVideoIdReturnsAny() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello"));
        LyricsCache.put(track, "TestProvider", lyrics, null);
        Lyrics result = LyricsCache.get(track, "TestProvider", "anyVideo");
        assertNotNull(result);
    }

    // ── translation cache ──

    @Test
    public void translationPutThenGetRoundTrip() {
        List<String> sourceLines = List.of("line 1", "line 2");
        List<String> translation = List.of("translated line 1", "translated line 2");
        LyricsCache.putTranslation(track, "TestProvider", "en", sourceLines, translation);
        List<String> result = LyricsCache.getTranslation(track, "TestProvider", "en", sourceLines);
        assertNotNull(result);
        assertEquals(translation, result);
    }

    @Test
    public void translationGetReturnsNullWhenMissing() {
        assertNull(LyricsCache.getTranslation(track, "TestProvider", "en", List.of("line 1")));
    }

    // ── section AI cache ──

    @Test
    public void sectionAIPutThenGetRoundTrip() {
        List<String> sourceLines = List.of("line1", "line2", "line3");
        List<String> sections = Arrays.asList("Verse", null, "Chorus");
        LyricsCache.putSectionAI(track, "TestProvider", sourceLines, sections);
        List<String> result = LyricsCache.getSectionAI(track, "TestProvider", sourceLines);
        assertNotNull(result);
        // null elements become the string "null" when written to the file cache.
        assertEquals(3, result.size());
        assertEquals("Verse", result.get(0));
        assertEquals("Chorus", result.get(2));
    }

    @Test
    public void sectionAIGetReturnsNullWhenMissing() {
        assertNull(LyricsCache.getSectionAI(track, "TestProvider", List.of("line1")));
    }

    // ── romanization cache ──

    @Test
    public void romanizationPutThenGetRoundTrip() {
        List<String> sourceLines = List.of("original line");
        List<LyricsLine> romanization = List.of(
                new LyricsLine(0, "romanized line"));
        LyricsCache.putRomanization(track, "TestProvider", sourceLines, romanization);
        List<LyricsLine> result = LyricsCache.getRomanization(track, "TestProvider", sourceLines);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void romanizationGetReturnsNullWhenMissing() {
        assertNull(LyricsCache.getRomanization(track, "TestProvider", List.of("line 1")));
    }

    @Test
    public void translationPutSanitizesEmbeddedNewlines() {
        List<String> sourceLines = List.of("first", "second");
        List<String> translation = List.of("first line\nwrapped", "second line\r\nwrapped");
        LyricsCache.putTranslation(track, "TestProvider", "en", sourceLines, translation);
        List<String> result = LyricsCache.getTranslation(track, "TestProvider", "en", sourceLines);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("first line wrapped", result.get(0));
        assertEquals("second line wrapped", result.get(1));
    }

    @Test
    public void wordRomanizationPutThenGetRoundTrip() {
        List<Word> origWords = List.of(
                new Word(100, 300, "君", null, false, null),
                new Word(300, 600, "と", null, true, null)
        );
        LyricsLine origLine = new LyricsLine(100, 600, "君と", origWords, "v1", false, false, "Verse");
        LyricsLine romLine = new LyricsLine(100, 600, "kimi to", List.of(
                new Word(100, 300, "君", "kimi", false, null),
                new Word(300, 600, "と", "to", true, null)
        ), "v1", false, false, "Verse");
        List<String> sourceLines = List.of("君と");

        LyricsCache.putWordRomanization(track, "TestProvider", sourceLines, List.of(romLine));
        List<LyricsLine> restored = LyricsCache.getWordRomanization(
                track, "TestProvider", sourceLines, List.of(origLine));
        assertNotNull(restored);
        assertEquals(1, restored.size());
        assertEquals("kimi to", restored.get(0).text());
        assertEquals(2, restored.get(0).words().size());
        assertEquals("kimi", restored.get(0).words().get(0).romaji());
        assertEquals("to", restored.get(0).words().get(1).romaji());
        assertEquals(100, restored.get(0).words().get(0).startMs());
        assertEquals("v1", restored.get(0).agentId());
    }

    // ── preference cache ──

    @Test
    public void preferenceGetReturnsNullWhenMissing() {
        assertNull(LyricsCache.getPreference("video123", track));
    }

    // ── clear / size ──

    @Test
    public void clearRemovesAllEntries() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello"));
        LyricsCache.put(track, "TestProvider", lyrics, null);
        assertTrue(LyricsCache.size() > 0);
        long removed = LyricsCache.clear();
        assertTrue(removed > 0);
        assertNull(LyricsCache.get(track, "TestProvider", null));
    }

    @Test
    public void sizeReturnsZeroWhenEmpty() {
        long initial = LyricsCache.size();
        // After clear, size should be 0 or close to it.
        LyricsCache.clear();
        assertEquals(0, LyricsCache.size());
    }

    @Test
    public void statsAndBucketClearSeparateLyricsTranslationAndTransliteration() {
        LyricsCache.clear();
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello"));
        LyricsCache.put(track, "TestProvider", lyrics, null);
        LyricsCache.putTranslation(track, "TestProvider", "en", List.of("hello"), List.of("halo"));
        LyricsCache.putRomanization(track, "TestProvider", List.of("hello"),
                List.of(new LyricsLine(0, "helo")));

        LyricsCache.CacheStats stats = LyricsCache.stats();
        assertEquals(1, stats.lyrics().items());
        assertEquals(1, stats.translation().items());
        assertEquals(1, stats.transliteration().items());
        assertEquals(3, stats.total().items());
        assertTrue(stats.lyrics().bytes() > 0);
        assertTrue(stats.translation().bytes() > 0);
        assertTrue(stats.transliteration().bytes() > 0);

        assertTrue(LyricsCache.clear(LyricsCache.CacheBucket.TRANSLATION) > 0);
        assertNull(LyricsCache.getTranslation(track, "TestProvider", "en", List.of("hello")));
        assertNotNull(LyricsCache.getRomanization(track, "TestProvider", List.of("hello")));
        assertNotNull(LyricsCache.get(track, "TestProvider", null));

        LyricsCache.CacheStats afterTransClear = LyricsCache.stats();
        assertEquals(1, afterTransClear.lyrics().items());
        assertEquals(0, afterTransClear.translation().items());
        assertEquals(1, afterTransClear.transliteration().items());
        assertEquals(2, afterTransClear.total().items());

        assertTrue(LyricsCache.clear(LyricsCache.CacheBucket.TRANSLITERATION) > 0);
        assertNull(LyricsCache.getRomanization(track, "TestProvider", List.of("hello")));
        assertNotNull(LyricsCache.get(track, "TestProvider", null));

        LyricsCache.CacheStats afterRomaClear = LyricsCache.stats();
        assertEquals(1, afterRomaClear.lyrics().items());
        assertEquals(0, afterRomaClear.transliteration().items());
        assertEquals(1, afterRomaClear.total().items());

        assertTrue(LyricsCache.clear(LyricsCache.CacheBucket.LYRICS) > 0);
        assertEquals(0, LyricsCache.stats().total().items());
    }
}

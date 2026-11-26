/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

/** Unit tests for the static helpers of {@link UnisonProvider}: base64Url, toHex, sourceUrl, search ranking. */
public class UnisonProviderStaticTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        LyricsRequests.setCustomMatchMode(false);
    }

    @After
    public void tearDown() {
        LyricsRequests.setCustomMatchMode(false);
    }

    // ── sourceUrl ──

    @Test
    public void sourceUrlBuildsUrl() {
        assertEquals("https://unison.boidu.dev/song/abc123",
                UnisonProvider.sourceUrl("abc123"));
    }

    // ── toHex ──

    @Test
    public void toHexEmpty() {
        assertEquals("", UnisonProvider.toHex(new byte[0]));
    }

    @Test
    public void toHexSingleByte() {
        assertEquals("ff", UnisonProvider.toHex(new byte[]{(byte) 0xFF}));
    }

    @Test
    public void toHexZeroByte() {
        assertEquals("00", UnisonProvider.toHex(new byte[]{0}));
    }

    @Test
    public void toHexMultipleBytes() {
        assertEquals("deadbeef", UnisonProvider.toHex(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}));
    }

    @Test
    public void toHexLowercase() {
        assertEquals("0a1b", UnisonProvider.toHex(new byte[]{(byte) 0x0A, (byte) 0x1B}));
    }

    // base64Url uses android.util.Base64 which returns null in unit tests
    // (unitTests.isReturnDefaultValues = true), so it is not testable here.

    // ── search ranking ──

    private static JSONObject hit(String videoId, String song, String artist, long duration,
                                  boolean hidden) throws JSONException {
        return new JSONObject()
                .put("videoId", videoId)
                .put("song", song)
                .put("artist", artist)
                .put("duration", duration)
                .put("hidden", hidden)
                .put("format", "lrc")
                .put("lyrics", "[00:01.00]Hello");
    }

    @Test
    public void rankSearchHitsPrefersMatchingArtist() throws JSONException {
        JSONArray data = new JSONArray()
                .put(hit("wrong", "Stay", "Someone Else", 200, false))
                .put(hit("right", "Stay", "The Kid LAROI", 200, false));

        List<UnisonProvider.SearchHit> hits = UnisonProvider.rankSearchHits(
                data, new TrackInfo("Stay", "The Kid LAROI", "", 200));

        assertEquals(1, hits.size());
        assertEquals("right", hits.get(0).videoId());
        assertEquals("The Kid LAROI", hits.get(0).artist());
        assertEquals("lrc", hits.get(0).variant().optString("format"));
    }

    @Test
    public void rankSearchHitsSkipsHiddenEntries() throws JSONException {
        JSONArray data = new JSONArray()
                .put(hit("hidden", "Stay", "The Kid LAROI", 200, true))
                .put(hit("shown", "Stay", "The Kid LAROI", 200, false));

        List<UnisonProvider.SearchHit> hits = UnisonProvider.rankSearchHits(
                data, new TrackInfo("Stay", "The Kid LAROI", "", 200));

        assertEquals(1, hits.size());
        assertEquals("shown", hits.get(0).videoId());
    }

    @Test
    public void rankSearchHitsKeepsOnlyTheFallbackWhenNothingPasses() throws JSONException {
        JSONArray data = new JSONArray()
                .put(hit("a", "Completely Different Song", "Someone Else", 200, false))
                .put(hit("b", "Stay", "Another Artist", 200, false));

        List<UnisonProvider.SearchHit> hits = UnisonProvider.rankSearchHits(
                data, new TrackInfo("Stay", "The Kid LAROI", "", 200));

        assertEquals(1, hits.size());
        assertEquals("b", hits.get(0).videoId());
    }

    @Test
    public void rankSearchHitsSkipsEntriesWithoutVideoId() throws JSONException {
        JSONArray data = new JSONArray()
                .put(new JSONObject().put("song", "Stay").put("artist", "The Kid LAROI"))
                .put(hit("right", "Stay", "The Kid LAROI", 200, false));

        List<UnisonProvider.SearchHit> hits = UnisonProvider.rankSearchHits(
                data, new TrackInfo("Stay", "The Kid LAROI", "", 200));

        assertEquals(1, hits.size());
        assertEquals("right", hits.get(0).videoId());
    }

    @Test
    public void rankSearchHitsEmptyForNullData() {
        assertTrue(UnisonProvider.rankSearchHits(
                null, new TrackInfo("Stay", "The Kid LAROI", "", 200)).isEmpty());
    }
}

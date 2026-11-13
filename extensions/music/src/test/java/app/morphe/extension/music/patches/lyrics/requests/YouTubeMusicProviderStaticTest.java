/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/** Unit tests for the static parsing helpers of {@link YouTubeMusicProvider}: extractLyricsBrowseId, parseSourceProvider, navigateTimedLyricsModel, extractPlainTextLyrics, parseTimedLyrics, parseTimestamp. */
public class YouTubeMusicProviderStaticTest {

    // ── parseSourceProvider ──

    @Test
    public void parseSourceProviderExtractsProvider() {
        assertEquals("Genius", YouTubeMusicProvider.parseSourceProvider("Source: Genius"));
    }

    @Test
    public void parseSourceProviderTrimsProvider() {
        assertEquals("Genius", YouTubeMusicProvider.parseSourceProvider("Source:   Genius  "));
    }

    @Test
    public void parseSourceProviderNoPrefixReturnsNull() {
        assertNull(YouTubeMusicProvider.parseSourceProvider("Genius"));
    }

    @Test
    public void parseSourceProviderEmptyProviderReturnsNull() {
        assertNull(YouTubeMusicProvider.parseSourceProvider("Source:   "));
    }

    // ── extractLyricsBrowseId ──

    @Test
    public void extractLyricsBrowseIdFindsLyricsTab() throws JSONException {
        JSONObject browseEndpoint = new JSONObject()
                .put("browseId", "MPLYT_xyz")
                .put("browseEndpointContextSupportedConfigs", new JSONObject()
                        .put("browseEndpointContextMusicConfig", new JSONObject()
                                .put("pageType", "MUSIC_PAGE_TYPE_TRACK_LYRICS")));
        JSONObject tab = new JSONObject()
                .put("tabRenderer", new JSONObject()
                        .put("endpoint", new JSONObject()
                                .put("browseEndpoint", browseEndpoint)));
        JSONObject response = buildBrowseResponse(tab);

        assertEquals("MPLYT_xyz", YouTubeMusicProvider.extractLyricsBrowseId(response));
    }

    @Test
    public void extractLyricsBrowseIdSkipsNonLyricsTabs() throws JSONException {
        JSONObject nonLyricsEndpoint = new JSONObject()
                .put("browseId", "MPLYT_non_lyrics")
                .put("browseEndpointContextSupportedConfigs", new JSONObject()
                        .put("browseEndpointContextMusicConfig", new JSONObject()
                                .put("pageType", "MUSIC_PAGE_TYPE_OTHER")));
        JSONObject lyricsEndpoint = new JSONObject()
                .put("browseId", "MPLYT_lyrics")
                .put("browseEndpointContextSupportedConfigs", new JSONObject()
                        .put("browseEndpointContextMusicConfig", new JSONObject()
                                .put("pageType", "MUSIC_PAGE_TYPE_TRACK_LYRICS")));
        JSONObject tab1 = new JSONObject().put("tabRenderer", new JSONObject()
                .put("endpoint", new JSONObject().put("browseEndpoint", nonLyricsEndpoint)));
        JSONObject tab2 = new JSONObject().put("tabRenderer", new JSONObject()
                .put("endpoint", new JSONObject().put("browseEndpoint", lyricsEndpoint)));
        JSONObject response = buildBrowseResponse(tab1, tab2);

        assertEquals("MPLYT_lyrics", YouTubeMusicProvider.extractLyricsBrowseId(response));
    }

    @Test
    public void extractLyricsBrowseIdMissingContentsReturnsNull() throws JSONException {
        JSONObject response = new JSONObject();
        assertNull(YouTubeMusicProvider.extractLyricsBrowseId(response));
    }

    @Test
    public void extractLyricsBrowseIdNoLyricsTabReturnsNull() throws JSONException {
        JSONObject endpoint = new JSONObject()
                .put("browseId", "MPLYT_other")
                .put("browseEndpointContextSupportedConfigs", new JSONObject()
                        .put("browseEndpointContextMusicConfig", new JSONObject()
                                .put("pageType", "MUSIC_PAGE_TYPE_OTHER")));
        JSONObject tab = new JSONObject().put("tabRenderer", new JSONObject()
                .put("endpoint", new JSONObject().put("browseEndpoint", endpoint)));
        JSONObject response = buildBrowseResponse(tab);

        assertNull(YouTubeMusicProvider.extractLyricsBrowseId(response));
    }

    private static JSONObject buildBrowseResponse(JSONObject... tabs) throws JSONException {
        JSONArray tabArray = new JSONArray();
        for (JSONObject tab : tabs) {
            tabArray.put(tab);
        }
        return new JSONObject()
                .put("contents", new JSONObject()
                        .put("singleColumnMusicWatchNextResultsRenderer", new JSONObject()
                                .put("tabbedRenderer", new JSONObject()
                                        .put("watchNextTabbedResultsRenderer", new JSONObject()
                                                .put("tabs", tabArray)))));
    }

    // ── navigateTimedLyricsModel ──

    @Test
    public void navigateTimedLyricsModelFindsLyricsData() throws JSONException {
        JSONObject lyricsData = new JSONObject().put("lyrics", "Hello");
        JSONObject response = buildTimedLyricsModelResponse(lyricsData);

        JSONObject result = YouTubeMusicProvider.navigateTimedLyricsModel(response);
        assertNotNull(result);
        assertEquals("Hello", result.optString("lyrics"));
    }

    @Test
    public void navigateTimedLyricsModelMissingReturnsNull() throws JSONException {
        JSONObject response = new JSONObject();
        assertNull(YouTubeMusicProvider.navigateTimedLyricsModel(response));
    }

    @Test
    public void navigateTimedLyricsModelPartialMissingReturnsNull() throws JSONException {
        JSONObject response = new JSONObject()
                .put("contents", new JSONObject());
        assertNull(YouTubeMusicProvider.navigateTimedLyricsModel(response));
    }

    private static JSONObject buildTimedLyricsModelResponse(JSONObject lyricsData) throws JSONException {
        return new JSONObject()
                .put("contents", new JSONObject()
                        .put("elementRenderer", new JSONObject()
                                .put("newElement", new JSONObject()
                                        .put("type", new JSONObject()
                                                .put("componentType", new JSONObject()
                                                        .put("model", new JSONObject()
                                                                .put("timedLyricsModel", new JSONObject()
                                                                        .put("lyricsData", lyricsData))))))));
    }

    // ── extractPlainTextLyrics ──

    @Test
    public void extractPlainTextLyricsFindsDescription() throws JSONException {
        JSONObject run = new JSONObject().put("text", "Hello World");
        JSONObject description = new JSONObject().put("runs", new JSONArray().put(run));
        JSONObject shelf = new JSONObject()
                .put("musicDescriptionShelfRenderer", new JSONObject()
                        .put("description", description));
        JSONObject response = buildSectionListResponse(shelf);

        assertEquals("Hello World", YouTubeMusicProvider.extractPlainTextLyrics(response));
    }

    @Test
    public void extractPlainTextLyricsMultipleRuns() throws JSONException {
        JSONObject response = buildSectionListResponse(new JSONObject()
                .put("musicDescriptionShelfRenderer", new JSONObject()
                        .put("description", new JSONObject()
                                .put("runs", new JSONArray()
                                        .put(new JSONObject().put("text", "Hello "))
                                        .put(new JSONObject().put("text", "World"))))));

        assertEquals("Hello World", YouTubeMusicProvider.extractPlainTextLyrics(response));
    }

    @Test
    public void extractPlainTextLyricsNoShelfReturnsNull() throws JSONException {
        JSONObject response = new JSONObject().put("contents", new JSONObject()
                .put("sectionListRenderer", new JSONObject()
                        .put("contents", new JSONArray())));
        assertNull(YouTubeMusicProvider.extractPlainTextLyrics(response));
    }

    @Test
    public void extractPlainTextLyricsMissingContentsReturnsNull() throws JSONException {
        JSONObject response = new JSONObject();
        assertNull(YouTubeMusicProvider.extractPlainTextLyrics(response));
    }

    private static JSONObject buildSectionListResponse(JSONObject shelf) throws JSONException {
        return new JSONObject()
                .put("contents", new JSONObject()
                        .put("sectionListRenderer", new JSONObject()
                                .put("contents", new JSONArray().put(shelf))));
    }

    // ── parseTimestamp ──

    @Test
    public void parseTimestampFindsKey() throws JSONException {
        JSONObject cueRange = new JSONObject().put("startTimeMilliseconds", 5000);
        assertEquals(5000L, YouTubeMusicProvider.parseTimestamp(cueRange, "startTimeMilliseconds"));
    }

    @Test
    public void parseTimestampMissingKeyReturnsNoTime() throws JSONException {
        JSONObject cueRange = new JSONObject().put("other", 5000);
        assertEquals(LyricsLine.NO_TIME, YouTubeMusicProvider.parseTimestamp(cueRange, "startTimeMilliseconds"));
    }

    @Test
    public void parseTimestampNullCueRangeReturnsNoTime() {
        assertEquals(LyricsLine.NO_TIME, YouTubeMusicProvider.parseTimestamp(null, "startTimeMilliseconds"));
    }

    @Test
    public void parseTimestampEndTime() throws JSONException {
        JSONObject cueRange = new JSONObject().put("endTimeMilliseconds", 7000);
        assertEquals(7000L, YouTubeMusicProvider.parseTimestamp(cueRange, "endTimeMilliseconds"));
    }

    // ── parseTimedLyrics ──

    @Test
    public void parseTimedLyricsBuildsLines() throws JSONException {
        JSONObject entry1 = new JSONObject()
                .put("lyricLine", "First line")
                .put("cueRange", new JSONObject()
                        .put("startTimeMilliseconds", 1000)
                        .put("endTimeMilliseconds", 2000));
        JSONObject entry2 = new JSONObject()
                .put("lyricLine", "Second line")
                .put("cueRange", new JSONObject()
                        .put("startTimeMilliseconds", 2000)
                        .put("endTimeMilliseconds", 3000));
        JSONArray timedArray = new JSONArray().put(entry1).put(entry2);

        List<LyricsLine> lines = YouTubeMusicProvider.parseTimedLyrics(timedArray);
        assertEquals(2, lines.size());
        assertEquals("First line", lines.get(0).text());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals(2000L, lines.get(0).endTimeMs());
        assertEquals("Second line", lines.get(1).text());
        assertEquals(2000L, lines.get(1).startTimeMs());
        assertEquals(3000L, lines.get(1).endTimeMs());
    }

    @Test
    public void parseTimedLyricsSkipsEmptyLines() throws JSONException {
        JSONObject entry = new JSONObject().put("lyricLine", "  ");
        JSONArray timedArray = new JSONArray().put(entry);

        List<LyricsLine> lines = YouTubeMusicProvider.parseTimedLyrics(timedArray);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void parseTimedLyricsSkipsNullEntry() throws JSONException {
        JSONArray timedArray = new JSONArray().put(JSONObject.NULL);

        List<LyricsLine> lines = YouTubeMusicProvider.parseTimedLyrics(timedArray);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void parseTimedLyricsMissingCueRangeUsesNoTime() throws JSONException {
        JSONObject entry = new JSONObject().put("lyricLine", "No timing");
        JSONArray timedArray = new JSONArray().put(entry);

        List<LyricsLine> lines = YouTubeMusicProvider.parseTimedLyrics(timedArray);
        assertEquals(1, lines.size());
        assertEquals(LyricsLine.NO_TIME, lines.get(0).startTimeMs());
        assertEquals(LyricsLine.NO_TIME, lines.get(0).endTimeMs());
    }

    @Test
    public void parseTimedLyricsEmptyArray() {
        List<LyricsLine> lines = YouTubeMusicProvider.parseTimedLyrics(new JSONArray());
        assertTrue(lines.isEmpty());
    }
}

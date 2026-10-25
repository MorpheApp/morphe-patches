/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GetPlaylistItemsRequestTest {

    private static JSONObject response(JSONArray rows) throws Exception {
        return new JSONObject().put("contents", new JSONObject().put("singleColumnBrowseResultsRenderer",
                new JSONObject().put("tabs", new JSONArray().put(new JSONObject().put("tabRenderer",
                        new JSONObject().put("content", new JSONObject().put("sectionListRenderer",
                                new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                                        .put("itemSectionRenderer", new JSONObject().put("contents",
                                                new JSONArray().put(new JSONObject().put("playlistVideoListRenderer",
                                                        new JSONObject().put("contents", rows)))))))))))))));
    }

    private static JSONObject row(String videoId, String duration) throws Exception {
        return new JSONObject().put("playlistVideoRenderer", new JSONObject()
                .put("videoId", videoId)
                .put("setVideoId", "set-" + videoId)
                .put("title", new JSONObject().put("runs", new JSONArray()
                        .put(new JSONObject().put("text", "Title "))
                        .put(new JSONObject().put("text", videoId))))
                .put("shortBylineText", new JSONObject().put("simpleText", "Channel"))
                .put("lengthText", new JSONObject().put("simpleText", duration)));
    }

    @Test
    void parsesOrderedPlaylistMetadata() throws Exception {
        var items = GetPlaylistItemsRequest.parseItems(response(new JSONArray()
                .put(row("abcdefghijk", "1:02:03"))
                .put(row("lmnopqrstuv", "04:05"))));

        assertEquals(2, items.size());
        assertEquals("abcdefghijk", items.get(0).videoId());
        assertEquals("Title abcdefghijk", items.get(0).title());
        assertEquals("Channel", items.get(0).channel());
        assertEquals(3723, items.get(0).durationSeconds());
        assertEquals(245, items.get(1).durationSeconds());
    }

    @Test
    void ignores nonVideoRowsAndMissingContents() throws Exception {
        var items = GetPlaylistItemsRequest.parseItems(response(new JSONArray()
                .put(new JSONObject().put("continuationItemRenderer", new JSONObject()))));
        assertNull(items);
        assertNull(GetPlaylistItemsRequest.parseItems(new JSONObject()));
    }
}

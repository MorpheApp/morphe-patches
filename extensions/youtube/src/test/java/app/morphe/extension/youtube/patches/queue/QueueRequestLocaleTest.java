/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRoutes;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistRoutes;

public class QueueRequestLocaleTest {
    @Test
    public void metadataSearchKeepsRegionalAppLanguageInsteadOfSystemLanguage() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            var locale = Locale.forLanguageTag("en-IN");
            var body = new JSONObject(new String(ChannelIdRoutes.createSearchBody("A video", locale), StandardCharsets.UTF_8));
            var client = body.getJSONObject("context").getJSONObject("client");
            assertEquals("en-IN", client.getString("hl"));
            assertEquals("IN", client.getString("gl"));
            assertEquals("ANDROID", client.getString("clientName"));
            assertEquals("A video", body.getString("query"));
            assertTrue(body.getBoolean("contentCheckOk"));

            // ID-only player lookups retain their original context.
            var player = new JSONObject(new String(ChannelIdRoutes.createBody("abcdefghijk"), StandardCharsets.UTF_8));
            assertEquals("en", player.getJSONObject("context").getJSONObject("client").getString("hl"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void mixDisplayLocaleDoesNotChangePlaylistEditRequests() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            var body = new JSONObject(new String(PlaylistRoutes.getSetVideoIdBody(
                    "abcdefghijk", "RDabcdefghijk", Locale.forLanguageTag("en-IN")), StandardCharsets.UTF_8));
            var client = body.getJSONObject("context").getJSONObject("client");
            assertEquals("en-IN", client.getString("hl"));
            assertEquals("IN", client.getString("gl"));
            assertEquals("RDabcdefghijk", body.getString("playlistId"));
            assertEquals("abcdefghijk", body.getString("videoId"));
            assertTrue(body.getBoolean("racyCheckOk"));

            var edit = new JSONObject(new String(PlaylistRoutes.getSetVideoIdBody(
                    "abcdefghijk", "PLsaved"), StandardCharsets.UTF_8));
            var editClient = edit.getJSONObject("context").getJSONObject("client");
            assertEquals("en", editClient.getString("hl"));
            assertEquals("US", editClient.getString("gl"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}

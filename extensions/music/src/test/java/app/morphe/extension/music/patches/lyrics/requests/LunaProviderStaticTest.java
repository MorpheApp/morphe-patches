/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Unit tests for the static helpers of {@link LunaProvider}: firstArtistName and isOriginalTrack. */
public class LunaProviderStaticTest {

    // ── firstArtistName ──

    @Test
    public void firstArtistNameExtracts() throws JSONException {
        JSONObject artist = new JSONObject().put("name", "The Artist");
        JSONObject trackObj = new JSONObject().put("artists", new JSONArray().put(artist));
        assertEquals("The Artist", LunaProvider.firstArtistName(trackObj));
    }

    @Test
    public void firstArtistNameNoArtistsReturnsEmpty() throws JSONException {
        JSONObject trackObj = new JSONObject();
        assertEquals("", LunaProvider.firstArtistName(trackObj));
    }

    @Test
    public void firstArtistNameEmptyArtistsReturnsEmpty() throws JSONException {
        JSONObject trackObj = new JSONObject().put("artists", new JSONArray());
        assertEquals("", LunaProvider.firstArtistName(trackObj));
    }

    @Test
    public void firstArtistNameNullArtistObjectReturnsEmpty() throws JSONException {
        JSONObject trackObj = new JSONObject().put("artists", new JSONArray().put(JSONObject.NULL));
        assertEquals("", LunaProvider.firstArtistName(trackObj));
    }

    @Test
    public void firstArtistNameMissingNameReturnsEmpty() throws JSONException {
        JSONObject artist = new JSONObject();
        JSONObject trackObj = new JSONObject().put("artists", new JSONArray().put(artist));
        assertEquals("", LunaProvider.firstArtistName(trackObj));
    }

    @Test
    public void firstArtistNameTakesFirstOnly() throws JSONException {
        JSONObject first = new JSONObject().put("name", "First");
        JSONObject second = new JSONObject().put("name", "Second");
        JSONObject trackObj = new JSONObject()
                .put("artists", new JSONArray().put(first).put(second));
        assertEquals("First", LunaProvider.firstArtistName(trackObj));
    }

    // ── isOriginalTrack ──

    @Test
    public void isOriginalTrackTrue() throws JSONException {
        JSONObject labelInfo = new JSONObject().put("is_original", true);
        JSONObject trackObj = new JSONObject().put("label_info", labelInfo);
        assertTrue(LunaProvider.isOriginalTrack(trackObj));
    }

    @Test
    public void isOriginalTrackFalse() throws JSONException {
        JSONObject labelInfo = new JSONObject().put("is_original", false);
        JSONObject trackObj = new JSONObject().put("label_info", labelInfo);
        assertFalse(LunaProvider.isOriginalTrack(trackObj));
    }

    @Test
    public void isOriginalTrackMissingLabelInfo() throws JSONException {
        JSONObject trackObj = new JSONObject();
        assertFalse(LunaProvider.isOriginalTrack(trackObj));
    }

    @Test
    public void isOriginalTrackMissingIsOriginal() throws JSONException {
        JSONObject labelInfo = new JSONObject();
        JSONObject trackObj = new JSONObject().put("label_info", labelInfo);
        assertFalse(LunaProvider.isOriginalTrack(trackObj));
    }

    @Test
    public void isOriginalTrackNullLabelInfo() throws JSONException {
        JSONObject trackObj = new JSONObject().put("label_info", null);
        assertFalse(LunaProvider.isOriginalTrack(trackObj));
    }
}

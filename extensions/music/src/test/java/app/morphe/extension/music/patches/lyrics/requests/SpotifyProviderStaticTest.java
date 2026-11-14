/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/** Unit tests for the static helpers of {@link SpotifyProvider}: parseStartTimeMs and decimalStringFromInts. */
public class SpotifyProviderStaticTest {

    // ── parseStartTimeMs ──

    @Test
    public void parseStartTimeMsValid() throws JSONException {
        JSONObject obj = new JSONObject().put("startTimeMs", "5000");
        assertEquals(5000L, SpotifyProvider.parseStartTimeMs(obj));
    }

    @Test
    public void parseStartTimeMsMissingKeyReturnsNoTime() throws JSONException {
        JSONObject obj = new JSONObject().put("other", "5000");
        assertEquals(LyricsLine.NO_TIME, SpotifyProvider.parseStartTimeMs(obj));
    }

    @Test
    public void parseStartTimeMsEmptyObjectReturnsNoTime() throws JSONException {
        JSONObject obj = new JSONObject();
        assertEquals(LyricsLine.NO_TIME, SpotifyProvider.parseStartTimeMs(obj));
    }

    @Test
    public void parseStartTimeMsEmptyStringReturnsNoTime() throws JSONException {
        JSONObject obj = new JSONObject().put("startTimeMs", "");
        assertEquals(LyricsLine.NO_TIME, SpotifyProvider.parseStartTimeMs(obj));
    }

    @Test
    public void parseStartTimeMsBlankStringReturnsNoTime() throws JSONException {
        JSONObject obj = new JSONObject().put("startTimeMs", "   ");
        assertEquals(LyricsLine.NO_TIME, SpotifyProvider.parseStartTimeMs(obj));
    }

    @Test
    public void parseStartTimeMsInvalidNumberReturnsNoTime() throws JSONException {
        JSONObject obj = new JSONObject().put("startTimeMs", "not-a-number");
        assertEquals(LyricsLine.NO_TIME, SpotifyProvider.parseStartTimeMs(obj));
    }

    // ── decimalStringFromInts ──

    @Test
    public void decimalStringFromIntsConcatenates() {
        assertEquals("123", SpotifyProvider.decimalStringFromInts(new int[]{1, 2, 3}));
    }

    @Test
    public void decimalStringFromIntsSingleDigit() {
        assertEquals("5", SpotifyProvider.decimalStringFromInts(new int[]{5}));
    }

    @Test
    public void decimalStringFromIntsMultipleDigits() {
        assertEquals("1000999", SpotifyProvider.decimalStringFromInts(new int[]{100, 0, 999}));
    }

    @Test
    public void decimalStringFromIntsZero() {
        assertEquals("0", SpotifyProvider.decimalStringFromInts(new int[]{0}));
    }

    @Test
    public void decimalStringFromIntsEmpty() {
        assertEquals("", SpotifyProvider.decimalStringFromInts(new int[]{}));
    }
}

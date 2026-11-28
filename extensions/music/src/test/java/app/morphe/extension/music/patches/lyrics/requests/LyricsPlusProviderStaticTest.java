/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

public class LyricsPlusProviderStaticTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static TrackInfo track(String title, String artist, String album, int duration) {
        return new TrackInfo(title, artist, album, duration);
    }

    @Test
    public void officialHostIsFirst() {
        assertEquals("https://lyricsplus.prjktla.my.id", LyricsPlusProvider.API_HOSTS[0]);
    }

    @Test
    public void binimumMirrorIsListed() {
        boolean found = false;
        for (String host : LyricsPlusProvider.API_HOSTS) {
            if ("https://lyricsplus.binimum.org".equals(host)) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void buildUrlEncodesSpacesAsPercent20() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Shape of You", "Ed Sheeran", "", 233));
        assertTrue(url.contains("title=Shape%20of%20You"));
        assertTrue(url.contains("artist=Ed%20Sheeran"));
        assertFalse(url.contains("+"));
    }

    @Test
    public void buildUrlOmitsAlbumWhenEmpty() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Hello", "Adele", "", 295));
        assertFalse(url.contains("album="));
    }

    @Test
    public void buildUrlIncludesAlbumWhenPresent() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Hello", "Adele", "25", 295));
        assertTrue(url.contains("&album=25&"));
    }

    @Test
    public void buildUrlOmitsDurationWhenZero() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Hello", "Adele", "25", 0));
        assertFalse(url.contains("duration="));
    }

    @Test
    public void buildUrlTrimsTrailingSlashFromHost() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.binimum.org/",
                track("Hello", "Adele", "25", 295));
        assertTrue(url.startsWith("https://lyricsplus.binimum.org/v1/ttml/get"));
    }

    @Test
    public void parseLyricsJsonAlignsRomanizationAndTranslationAcrossBgAndMissingLines() throws Exception {
        JSONObject firstItem = new JSONObject()
                .put("time", 1000)
                .put("duration", 2000)
                .put("text", "First line (yeah)")
                .put("syllabus", new JSONArray()
                        .put(new JSONObject().put("time", 1000).put("duration", 700).put("text", "First "))
                        .put(new JSONObject().put("time", 1700).put("duration", 700).put("text", "line "))
                        .put(new JSONObject().put("time", 2400).put("duration", 500).put("text", "yeah").put("isBackground", true)))
                .put("transliteration", new JSONObject()
                        .put("text", "first line")
                        .put("syllabus", new JSONArray()
                                .put(new JSONObject().put("text", "f"))
                                .put(new JSONObject().put("text", "l"))
                                .put(new JSONObject().put("text", "y"))));

        JSONObject secondItem = new JSONObject()
                .put("time", 4000)
                .put("duration", 1500)
                .put("text", "Second line")
                .put("translation", "Baris kedua")
                .put("transliteration", new JSONObject().put("text", "second line"));

        JSONObject root = new JSONObject()
                .put("lyrics", new JSONArray().put(firstItem).put(secondItem));

        Lyrics parsed = new LyricsPlusProvider().parseLyricsJson(root);
        assertNotNull(parsed);
        assertEquals(3, parsed.lines().size());
        assertNotNull(parsed.romanization());
        assertEquals(3, parsed.romanization().size());
        assertEquals("first line", parsed.romanization().get(0).text());
        assertEquals("", parsed.romanization().get(1).text());
        assertEquals("second line", parsed.romanization().get(2).text());

        assertEquals("f", parsed.lines().get(0).words().get(0).romaji());
        assertEquals("l", parsed.lines().get(0).words().get(1).romaji());
        assertEquals("y", parsed.lines().get(1).words().get(0).romaji());

        assertNotNull(parsed.translations());
        List<LyricsLine> defaultTrans = parsed.translations().get("default");
        assertNotNull(defaultTrans);
        assertEquals(3, defaultTrans.size());
        assertEquals("", defaultTrans.get(0).text());
        assertEquals("", defaultTrans.get(1).text());
        assertEquals("Baris kedua", defaultTrans.get(2).text());
    }

    @Test
    public void parseLyricsJsonSkipsIncompleteSyllableTransliterationWhenCountsDiffer() throws Exception {
        JSONObject item = new JSONObject()
                .put("time", 1000)
                .put("duration", 2000)
                .put("text", "風")
                .put("syllabus", new JSONArray()
                        .put(new JSONObject().put("time", 1000).put("duration", 1000).put("text", "風"))
                        .put(new JSONObject().put("time", 2000).put("duration", 1000).put("text", "の")))
                .put("transliteration", new JSONObject()
                        .put("text", "kaze no")
                        .put("syllabus", new JSONArray()
                                .put(new JSONObject().put("text", "kaze"))));

        Lyrics parsed = new LyricsPlusProvider().parseLyricsJson(
                new JSONObject().put("lyrics", new JSONArray().put(item)));
        assertNotNull(parsed);
        assertEquals(1, parsed.lines().size());
        assertNull(parsed.lines().get(0).words().get(0).romaji());
        assertNull(parsed.lines().get(0).words().get(1).romaji());
        assertNotNull(parsed.romanization());
        assertEquals("kaze no", parsed.romanization().get(0).text());
    }

    @Test
    public void parseLyricsJsonReturnsNullRomanizationWhenAbsent() throws Exception {
        JSONObject item = new JSONObject()
                .put("time", 1000)
                .put("duration", 1500)
                .put("text", "Plain line");

        Lyrics parsed = new LyricsPlusProvider().parseLyricsJson(
                new JSONObject().put("lyrics", new JSONArray().put(item)));
        assertNotNull(parsed);
        assertEquals(1, parsed.lines().size());
        assertNull(parsed.romanization());
        assertNull(parsed.translations());
    }
}

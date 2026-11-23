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

import org.junit.Test;

import java.util.Base64;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

import org.json.JSONArray;
import org.json.JSONObject;

public class KuwoProviderStaticTest {

    @Test
    public void buildLyricParamsProducesBase64() {
        String result = KuwoProvider.buildLyricParams("user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_123&lrcx=1");
        assertNotNull(result);
        assertTrue(!result.isEmpty());
        // must be valid base64
        Base64.getDecoder().decode(result);
    }

    @Test
    public void buildLyricParamsXorsWithYeelion() {
        String params = "rid=MUSIC_1";
        String encoded = KuwoProvider.buildLyricParams(params);
        byte[] decoded = Base64.getDecoder().decode(encoded);
        // XOR back with "yeelion"
        byte[] key = "yeelion".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] plain = new byte[decoded.length];
        for (int i = 0; i < decoded.length; i++) {
            plain[i] = (byte) (decoded[i] ^ key[i % key.length]);
        }
        assertEquals(params, new String(plain, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void parseScaleNoTagReturnsDefault() {
        int[] r = KuwoProvider.parseScale("no scale here");
        assertEquals(1, r[0]);
        assertEquals(1, r[1]);
    }

    @Test
    public void parseScaleOctalTag() {
        // 26 octal = 22 decimal; first = 2, second = 2
        int[] r = KuwoProvider.parseScale("[kuwo:26]");
        assertEquals(2, r[0]);
        assertEquals(2, r[1]);
    }

    @Test
    public void parseScaleNullReturnsDefault() {
        int[] r = KuwoProvider.parseScale(null);
        assertEquals(1, r[0]);
        assertEquals(1, r[1]);
    }

    @Test
    public void parseKuwoLrcNullReturnsNull() {
        assertNull(KuwoProvider.parseKuwoLrc(null, "t", "a", "al"));
    }

    @Test
    public void parseKuwoLrcEmptyReturnsNull() {
        assertNull(KuwoProvider.parseKuwoLrc("", "t", "a", "al"));
    }

    @Test
    public void parseKuwoLrcBasicLine() {
        String text = "[00:10.000]<10,20>hello <30,40>world\n";
        Lyrics lyrics = KuwoProvider.parseKuwoLrc(text, "Title", "Artist", "Album");
        assertNotNull(lyrics);
        assertEquals("Kuwo", lyrics.providerName());
        assertEquals(1, lyrics.lines().size());
        LyricsLine line = lyrics.lines().get(0);
        assertEquals(10000, line.startTimeMs());
        assertTrue(line.text().contains("hello"));
        assertTrue(line.text().contains("world"));
        assertEquals(2, line.words().size());
    }

    @Test
    public void parseKuwoLrcTranslationLine() {
        String text = "[00:10.000]<10,20>hello\n[00:10.000]<0,0>你好\n";
        Lyrics lyrics = KuwoProvider.parseKuwoLrc(text, "Title", "Artist", "Album");
        assertNotNull(lyrics);
        assertNotNull(lyrics.translations());
        assertTrue(lyrics.translations().containsKey("default"));
        assertEquals(1, lyrics.translations().get("default").size());
        assertEquals("你好", lyrics.translations().get("default").get(0).text());
    }

    @Test
    public void parseKuwoLrcTags() {
        String text = "[ti:My Title]\n[ar:My Artist]\n[00:05.000]line one\n";
        Lyrics lyrics = KuwoProvider.parseKuwoLrc(text, "Override", "OverrideA", "OverrideAl");
        assertNotNull(lyrics);
        // caller-provided title/artist/album override tags
        assertEquals(1, lyrics.lines().size());
    }

    @Test
    public void parseKuwoLrcScale() {
        String text = "[kuwo:26]\n[00:01.000]<10,20>hi\n";
        Lyrics lyrics = KuwoProvider.parseKuwoLrc(text, "t", "a", "al");
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
    }

    @Test
    public void parseMobileLrcNullReturnsNull() {
        assertNull(KuwoProvider.parseMobileLrc(null, "t", "a", "al"));
    }

    @Test
    public void parseMobileLrcEmptyReturnsNull() {
        assertNull(KuwoProvider.parseMobileLrc(new JSONArray(), "t", "a", "al"));
    }

    @Test
    public void parseMobileLrcBasic() {
        JSONArray arr = new JSONArray();
        JSONObject o1 = new JSONObject();
        try {
            o1.put("time", "10.5");
            o1.put("lineLyric", "Hello");
            arr.put(o1);
            JSONObject o2 = new JSONObject();
            o2.put("time", "20");
            o2.put("lineLyric", "World");
            arr.put(o2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Lyrics lyrics = KuwoProvider.parseMobileLrc(arr, "Title", "Artist", "Album");
        assertNotNull(lyrics);
        assertEquals(2, lyrics.lines().size());
        assertEquals(10500, lyrics.lines().get(0).startTimeMs());
    }

    @Test
    public void decryptLyricsNullReturnsNull() {
        assertNull(KuwoProvider.decryptLyrics(null));
    }

    @Test
    public void decryptLyricsEmptyReturnsNull() {
        assertNull(KuwoProvider.decryptLyrics(new byte[0]));
    }

    @Test
    public void decryptLyricsInvalidHeaderReturnsNull() {
        assertNull(KuwoProvider.decryptLyrics("not tp=content".getBytes()));
    }
}
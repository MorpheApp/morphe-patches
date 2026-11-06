/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class LyricsPreferenceTest {

    @Test
    public void recordAccessors() {
        Lyrics pref = new Lyrics(List.of(new LyricsLine(0, "text")), "p", false,
                null, null, null, null, null, null, null);
        LyricsPreference preference = new LyricsPreference(
                "custom title", "custom artist", pref, List.of("fp1", "fp2"),
                "fingerprint123", "trackKey", "videoId123");
        assertEquals("custom title", preference.queryTitle());
        assertEquals("custom artist", preference.queryArtist());
        assertEquals(pref, preference.preferred());
        assertEquals(List.of("fp1", "fp2"), preference.queue());
        assertEquals("fingerprint123", preference.fingerprint());
        assertEquals("trackKey", preference.trackKey());
        assertEquals("videoId123", preference.videoId());
    }

    @Test
    public void recordAllowsNullQueryFields() {
        Lyrics pref = new Lyrics(List.of(), "p", false,
                null, null, null, null, null, null, null);
        LyricsPreference preference = new LyricsPreference(
                null, null, pref, List.of(), null, null, null);
        assertNull(preference.queryTitle());
        assertNull(preference.queryArtist());
        assertNull(preference.fingerprint());
        assertNull(preference.videoId());
    }

    @Test
    public void recordEquality() {
        Lyrics pref = Lyrics.NOT_FOUND;
        LyricsPreference a = new LyricsPreference(null, null, pref, List.of(), null, null, null);
        LyricsPreference b = new LyricsPreference(null, null, pref, List.of(), null, null, null);
        assertEquals(a, b);
    }
}

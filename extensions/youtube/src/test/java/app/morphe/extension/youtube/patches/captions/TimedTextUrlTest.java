/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TimedTextUrlTest {
    private static final String URL =
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&sig=a%2Bb&tlang=de&fmt=srv3";

    @Test public void extractsIdentityWithoutChangingSignedQueryBytes() {
        TimedTextUrl url = TimedTextUrl.parse(URL + "&opaque=?tlang=de#tlang=de");
        assertNotNull(url);
        assertEquals("abcdefghijk", url.field("v"));
        assertEquals("en", url.field("lang"));
        assertEquals("de", url.field("tlang"));
        int start = url.targetValueOffset("de");
        assertEquals("de", (URL + "&opaque=?tlang=de#tlang=de").substring(start, start + 2));
        assertEquals(-1, url.targetValueOffset("fr"));
    }

    @Test public void rejectsDuplicateAndEncodedIdentityParameters() {
        for (String suffix : new String[]{
                "&tlang=fr", "&%74lang=fr", "&lang=fr", "&v=other",
                "&%6cang=en", "&name=a&name=b", "&tlang", "&tlang=%ZZ"
        }) {
            assertNull(suffix, TimedTextUrl.parse(URL + suffix));
        }
    }

    @Test public void onlyTrustsTheOfficialTimedtextHostAndPath() {
        assertNull(TimedTextUrl.parse(URL.replace("https:", "http:")));
        assertNull(TimedTextUrl.parse(URL.replace("www.youtube.com", "youtube.com.evil.example")));
        assertNull(TimedTextUrl.parse(URL.replace("/api/timedtext", "/watch")));
        assertNotNull(TimedTextUrl.parse(URL.replace("www.youtube.com", "youtube.com")));
    }
}

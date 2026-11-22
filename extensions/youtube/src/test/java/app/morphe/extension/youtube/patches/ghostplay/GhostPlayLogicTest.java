/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class GhostPlayLogicTest {

    @Before
    public void setUp() {
        GhostPlayPatch.resetForTests();
    }

    private static byte[] lengthDelimitedField(int fieldNumber, byte[] value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fieldNumber << 3) | 2);
        writeVarint(out, value.length);
        out.write(value);
        return out.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static Map<String, String> sampleAuthHeaders() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Authorization", "SAPISIDHASH secret");
        map.put("X-Goog-PageId", "1234567890");
        map.put("Cookie", "SID=abc; HSID=def");
        map.put("X-Goog-Visitor-Id", "real-visitor-id");
        map.put("Content-Type", "application/x-protobuf");
        return map;
    }

    @Test
    public void readsVideoIdFromDirectAndWrappedAndGzipProtobufBodies() throws Exception {
        byte[] directBody = lengthDelimitedField(2, "dQw4w9WgXcQ".getBytes(StandardCharsets.UTF_8));
        assertEquals("dQw4w9WgXcQ", GhostPlayPatch.readVideoIdFromBody(directBody, false));

        byte[] wrappedBody = lengthDelimitedField(2, directBody);
        assertEquals("dQw4w9WgXcQ", GhostPlayPatch.readVideoIdFromBody(wrappedBody, true));

        byte[] gzippedWrapped = gzip(wrappedBody);
        assertEquals("dQw4w9WgXcQ", GhostPlayPatch.readVideoIdFromBody(gzippedWrapped, true));
    }

    @Test
    public void stripsAuthHeadersOnlyForGhostTargetedVideoAndPreservesOriginalMap() throws Exception {
        GhostPlayPatch.addGhostVideoForTests("ghostVid123");
        assertTrue(GhostPlayPatch.containsGhostVideoForTests("ghostVid123"));
        GhostPlayPatch.unmarkGhostVideo("ghostVid123");
        assertFalse(GhostPlayPatch.containsGhostVideoForTests("ghostVid123"));
        GhostPlayPatch.addGhostVideoForTests("ghostVid123");

        Map<String, String> original = sampleAuthHeaders();
        byte[] ghostBody = lengthDelimitedField(2, "ghostVid123".getBytes(StandardCharsets.UTF_8));
        byte[] normalBody = lengthDelimitedField(2, "normalVid99".getBytes(StandardCharsets.UTF_8));

        Map<String, String> normalResult = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
                original, normalBody, false, false, false, false, false);
        assertSame(original, normalResult);

        Map<String, String> ghostResult = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
                original, ghostBody, false, false, false, false, false);
        assertNotSame(original, ghostResult);
        assertFalse(ghostResult.containsKey("Authorization"));
        assertFalse(ghostResult.containsKey("X-Goog-PageId"));
        assertFalse(ghostResult.containsKey("Cookie"));
        assertFalse(ghostResult.containsKey("X-Goog-Visitor-Id"));
        assertEquals("application/x-protobuf", ghostResult.get("Content-Type"));
        assertTrue(original.containsKey("Authorization"));
    }

    @Test
    public void autoGhostsShortsAndSearchAndProtectsAccountActions() throws Exception {
        Map<String, String> original = sampleAuthHeaders();
        byte[] inner = lengthDelimitedField(2, "shortVid001".getBytes(StandardCharsets.UTF_8));
        byte[] reelBody = lengthDelimitedField(2, inner);

        Map<String, String> reelResult = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch",
                original, reelBody, false, true, false, false, false);
        assertFalse(reelResult.containsKey("Authorization"));
        assertTrue(GhostPlayPatch.containsGhostVideoForTests("shortVid001"));

        // Session-mode playback must not pollute the per-video ghostVideoIds set.
        byte[] sessionInner = lengthDelimitedField(2, "sessionVid9".getBytes(StandardCharsets.UTF_8));
        GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/player",
                original, sessionInner, true, true, true, false, false);
        assertFalse(GhostPlayPatch.containsGhostVideoForTests("sessionVid9"));

        // Explicit account actions (browse, like, subscribe) are never stripped.
        Map<String, String> browseResult = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/browse",
                original, null, true, true, true, true, true);
        assertSame(original, browseResult);
    }

    @Test
    public void anonymizesResolveUrlLogEventOnesieAndStatsDuringGhostAndPendingLaunch() {
        Map<String, String> original = sampleAuthHeaders();

        Map<String, String> resolveNormal = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/navigation/resolve_url",
                original, null, false, false, false, false, false);
        assertSame(original, resolveNormal);

        Map<String, String> resolvePending = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/navigation/resolve_url",
                original, null, false, false, false, false, true);
        assertFalse(resolvePending.containsKey("Authorization"));

        Map<String, String> logNormal = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/log_event",
                original, null, false, false, false, false, false);
        assertSame(original, logNormal);

        Map<String, String> logGhost = GhostPlayPatch.processRequestHeaders(
                "https://youtubei.googleapis.com/youtubei/v1/log_event",
                original, null, false, false, false, true, false);
        assertFalse(logGhost.containsKey("Authorization"));

        GhostPlayPatch.addGhostVideoForTests("vidXYZ");
        Map<String, String> statsGhost = GhostPlayPatch.processRequestHeaders(
                "https://s.youtube.com/api/stats/watchtime?docid=vidXYZ&cpn=abc",
                original, null, false, false, false, false, false);
        assertFalse(statsGhost.containsKey("Authorization"));

        assertEquals("vidXYZ", GhostPlayPatch.extractQueryParam(
                "https://s.youtube.com/api/stats/watchtime?docid=vidXYZ&cpn=abc", "docid"));
        assertTrue(GhostPlayPatch.isNativeGhostTargetUrl("https://rr1.googlevideo.com/initplayback?source=youtube"));
        assertTrue(GhostPlayPatch.isNativeGhostTargetUrl("https://youtubei.googleapis.com/youtubei/v1/player"));
        assertFalse(GhostPlayPatch.isNativeGhostTargetUrl("https://rr1.googlevideo.com/videoplayback?sabr=1"));
    }

    @Test
    public void matchesAndRemovesChannelEntriesPrecisely() {
        GhostChannelStore.ChannelEntry ch1 = new GhostChannelStore.ChannelEntry(
                "UC1234567890123456789012",
                "Lofi Girl",
                "@LofiGirl",
                "https://yt3.ggpht.com/avatar.jpg",
                "14M subscribers",
                "Beats to relax/study to",
                List.of("lofi hip hop radio", "synthwave radio"),
                1000L
        );
        GhostChannelStore.ChannelEntry chSameNameDifferentId = new GhostChannelStore.ChannelEntry(
                "UC9999999999999999999999",
                "Lofi Girl",
                "@OtherLofi",
                "", "", "", List.of(), 2000L
        );

        List<GhostChannelStore.ChannelEntry> entries = new ArrayList<>(List.of(ch1, chSameNameDifferentId));
        assertTrue(GhostChannelStore.matchesEntries(entries, "UC1234567890123456789012", null));
        assertTrue(GhostChannelStore.matchesEntries(entries, null, "lofi girl"));
        assertTrue(GhostChannelStore.matchesEntries(entries, null, "@lofigirl"));
        assertFalse(GhostChannelStore.matchesEntries(entries, "UC0000000000000000000000", "Other"));

        assertTrue(ch1.matchesFilter("synthwave"));
        assertTrue(ch1.matchesFilter("@lofi"));
        assertFalse(ch1.matchesFilter("cooking"));

        // Removing by channelId must not remove a different channel that shares the same display name.
        assertTrue(GhostChannelStore.removeFromList(entries, "UC1234567890123456789012", "Lofi Girl"));
        assertEquals(1, entries.size());
        assertEquals("UC9999999999999999999999", entries.get(0).channelId());
    }

    @Test
    public void parsesChannelUrlsHandlesAndModernVideoTitles() {
        assertEquals("UC1234567890abcdefghijkl",
                GhostChannelSearchApi.extractChannelId("https://www.youtube.com/channel/UC1234567890abcdefghijkl"));
        assertNull(GhostChannelSearchApi.extractChannelId("lofi music"));

        assertTrue(GhostChannelSearchApi.looksLikeUrlOrHandle("@Google"));
        assertTrue(GhostChannelSearchApi.looksLikeUrlOrHandle("https://youtube.com/@Google"));
        assertFalse(GhostChannelSearchApi.looksLikeUrlOrHandle("Google Developers"));

        assertEquals("https://www.youtube.com/@Google", GhostChannelSearchApi.normalizeToChannelUrl("@Google"));
        assertEquals("https://youtube.com/@Google", GhostChannelSearchApi.normalizeToChannelUrl("youtube.com/@Google"));

        String modernBrowseJson = """
                {
                  "contents": {
                    "richGridRenderer": {
                      "contents": [
                        {
                          "richItemRenderer": {
                            "content": {
                              "lockupViewModel": {
                                "metadata": {
                                  "lockupMetadataViewModel": {
                                    "title": { "content": "Modern Lockup Video 1" }
                                  }
                                }
                              }
                            }
                          }
                        },
                        {
                          "richItemRenderer": {
                            "content": {
                              "videoRenderer": {
                                "title": { "runs": [ { "text": "Legacy Video 2" } ] }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """;
        List<String> extracted = GhostChannelSearchApi.extractVideoTitlesFromJson(modernBrowseJson, 3);
        assertEquals(2, extracted.size());
        assertEquals("Modern Lockup Video 1", extracted.get(0));
        assertEquals("Legacy Video 2", extracted.get(1));
    }

    @Test
    public void trimsHistoryByConfiguredLimitAndFormatsStorageSize() {
        assertEquals(100, GhostHistoryStore.parseLimit("100"));
        assertEquals(0, GhostHistoryStore.parseLimit("0"));
        assertEquals(GhostHistoryStore.DEFAULT_MAX_ENTRIES, GhostHistoryStore.parseLimit("invalid"));

        List<GhostHistoryStore.Entry> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            list.add(new GhostHistoryStore.Entry("vid" + i, "Title " + i, "Ch", 10_000L, 100_000L, i));
        }
        GhostHistoryStore.trimToLimit(list, 3);
        assertEquals(3, list.size());
        assertEquals("vid0", list.get(0).videoId());
        assertEquals("vid2", list.get(2).videoId());

        GhostHistoryStore.trimToLimit(list, 0);
        assertEquals(3, list.size());

        GhostHistoryStore.Entry sample = new GhostHistoryStore.Entry("vid1", "First Title", "Channel A", 90_000L, 180_000L, 1000L);
        assertEquals(50, sample.progressPercent());
        assertTrue(sample.matchesFilter("first"));
        assertFalse(sample.matchesFilter("nope"));

        assertEquals("512 B", GhostHistoryStore.formatSize(512L));
        assertEquals("10.0 KB", GhostHistoryStore.formatSize(10_240L));
        assertEquals("1:30", GhostHistoryDialog.formatDuration(90_000L));
        assertEquals("1:01:05", GhostHistoryDialog.formatDuration(3_665_000L));
        assertNull(GhostPlayPatch.extractQueryParam("https://example.com/noquery", "id"));
    }
}

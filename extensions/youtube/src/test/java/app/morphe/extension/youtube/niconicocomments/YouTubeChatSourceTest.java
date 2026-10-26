/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class YouTubeChatSourceTest {
    @Test
    public void parsesWatchPageConfigAndBalancedJson() throws Exception {
        String html = "<script>ytcfg.set({\"INNERTUBE_API_KEY\":\"key\"});"
                + "ytcfg.set({\"INNERTUBE_CONTEXT\":{\"client\":{"
                + "\"clientName\":\"WEB\",\"clientVersion\":\"1.2.3\"}}});</script>"
                + "<script>var ytInitialData = {\"text\":\"escaped \\\" } brace\"};</script>";

        JSONObject config = YouTubeChatSource.parseYoutubeConfig(html);
        JSONObject initialData = YouTubeChatSource.parseMarkedObject(
                html,
                "var ytInitialData ="
        );

        assertEquals("key", config.getString("INNERTUBE_API_KEY"));
        assertEquals(
                "1.2.3",
                config.getJSONObject("INNERTUBE_CONTEXT")
                        .getJSONObject("client")
                        .getString("clientVersion")
        );
        assertNotNull(initialData);
        assertEquals("escaped \" } brace", initialData.getString("text"));
    }

    @Test
    public void findsContinuationAndTimeoutRecursively() throws Exception {
        JSONObject value = new JSONObject(
                "{\"outer\":[{\"invalidationContinuationData\":{"
                        + "\"continuation\":\"next\",\"timeoutMs\":2345}}]}"
        );

        YouTubeChatSource.Continuation continuation = YouTubeChatSource.findContinuation(
                value,
                "timedContinuationData",
                "invalidationContinuationData"
        );

        assertNotNull(continuation);
        assertEquals("next", continuation.value);
        assertEquals(2345, continuation.timeoutMs);
    }

    @Test
    public void parsesLiveTextEmojiMembershipAndPaidColor() throws Exception {
        JSONArray actions = new JSONArray("["
                + "{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{"
                + "\"id\":\"member\",\"message\":{\"runs\":[{\"text\":\"hello \"},"
                + "{\"emoji\":{\"shortcuts\":[\":wave:\"],\"image\":{\"thumbnails\":["
                + "{\"url\":\"//emoji-small\",\"width\":16,\"height\":16},"
                + "{\"url\":\"https://emoji-large\",\"width\":48,\"height\":48}]}}}]},"
                + "\"authorBadges\":[{\"liveChatAuthorBadgeRenderer\":{\"customThumbnail\":{}}}]}}}},"
                + "{\"addChatItemAction\":{\"item\":{\"liveChatPaidMessageRenderer\":{"
                + "\"id\":\"paid\",\"message\":{\"simpleText\":\"thank you\"},"
                + "\"bodyBackgroundColor\":4278255360}}}}"
                + "]");

        List<NiconicoComment> comments = YouTubeChatSource.parseComments(actions);

        assertEquals(2, comments.size());
        assertEquals("hello :wave:", comments.get(0).text);
        assertTrue(comments.get(0).membership);
        assertEquals(2, comments.get(0).segments.size());
        assertFalse(comments.get(0).segments.get(0).isEmoji());
        assertTrue(comments.get(0).segments.get(1).isEmoji());
        assertEquals("https://emoji-large", comments.get(0).segments.get(1).imageUrl);
        assertEquals(-1, comments.get(0).videoOffsetMs);
        assertEquals("thank you", comments.get(1).text);
        assertEquals(0x6100ff00, comments.get(1).backgroundColor);
    }

    @Test
    public void preservesExactReplayOffsetAndNestedChatAction() throws Exception {
        JSONArray actions = new JSONArray("[{\"replayChatItemAction\":{"
                + "\"videoOffsetTimeMsec\":\"7449\",\"actions\":["
                + "{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{"
                + "\"id\":\"replay\",\"timestampText\":{\"simpleText\":\"0:07\"},"
                + "\"message\":{\"simpleText\":\"at seven seconds\"}}}}}]}}]");

        YouTubeChatSource.ReplayParseResult result =
                YouTubeChatSource.parseReplayComments(actions);

        assertEquals(7_449, result.maxVideoOffsetMs);
        assertEquals(1, result.comments.size());
        assertEquals(7_449, result.comments.get(0).videoOffsetMs);
        assertEquals("at seven seconds", result.comments.get(0).text);
    }

    @Test
    public void discardsNegativeReplayTimestampButKeepsRealZero() throws Exception {
        JSONArray actions = new JSONArray("["
                + "{\"replayChatItemAction\":{\"videoOffsetTimeMsec\":\"0\",\"actions\":["
                + "{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{"
                + "\"id\":\"countdown\",\"timestampText\":{\"simpleText\":\"-0:31\"},"
                + "\"message\":{\"simpleText\":\"before start\"}}}}}]}},"
                + "{\"replayChatItemAction\":{\"videoOffsetTimeMsec\":\"0\",\"actions\":["
                + "{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{"
                + "\"id\":\"zero\",\"timestampText\":{\"simpleText\":\"0:00\"},"
                + "\"message\":{\"simpleText\":\"at start\"}}}}}]}}]");

        YouTubeChatSource.ReplayParseResult result =
                YouTubeChatSource.parseReplayComments(actions);

        assertEquals(0, result.maxVideoOffsetMs);
        assertEquals(1, result.comments.size());
        assertEquals("zero", result.comments.get(0).id);
        assertEquals(0, result.comments.get(0).videoOffsetMs);
    }

    @Test
    public void advancesReplayWindowWhenOnlyPreStartCommentsAreFiltered() throws Exception {
        JSONArray actions = new JSONArray("[{\"replayChatItemAction\":{"
                + "\"videoOffsetTimeMsec\":\"0\",\"actions\":["
                + "{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{"
                + "\"id\":\"countdown\",\"timestampText\":{\"simpleText\":\"-1:00:34\"},"
                + "\"message\":{\"simpleText\":\"before start\"}}}}}]}}]");

        YouTubeChatSource.ReplayParseResult result =
                YouTubeChatSource.parseReplayComments(actions);

        assertEquals(0, result.maxVideoOffsetMs);
        assertTrue(result.comments.isEmpty());
    }

    @Test
    public void clampsLivePollingDelayToARealtimeWindow() {
        assertEquals(250, YouTubeChatSource.getLivePollDelayMs(0));
        assertEquals(100, YouTubeChatSource.getLivePollDelayMs(20));
        assertEquals(175, YouTubeChatSource.getLivePollDelayMs(175));
        assertEquals(250, YouTubeChatSource.getLivePollDelayMs(5_000));
    }
}

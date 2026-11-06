/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4430
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class TimedCommentsTest {
    private static final long LENGTH_MS = 4 * 60_000;

    @Test
    public void showsTheTextAfterTheTimeAtThatTime() {
        List<NiconicoComment> comments = TimedComments.parseComment("id", "1:17 nice part", LENGTH_MS);

        assertEquals(1, comments.size());
        assertEquals("nice part", comments.get(0).text);
        assertEquals(77_000, comments.get(0).videoOffsetMs);
        assertEquals("id#0", comments.get(0).id);
    }

    @Test
    public void usesTheTextBeforeATimeAtTheEnd() {
        List<NiconicoComment> comments = TimedComments.parseComment("id", "Congrats on 900M views 1:00", LENGTH_MS);

        assertEquals(1, comments.size());
        assertEquals("Congrats on 900M views", comments.get(0).text);
        assertEquals(60_000, comments.get(0).videoOffsetMs);
    }

    @Test
    public void keepsATimeInsideASentence() {
        List<NiconicoComment> comments = TimedComments.parseComment("id", "Who saw the hole at 2:09?", LENGTH_MS);

        assertEquals(1, comments.size());
        assertEquals("Who saw the hole at 2:09?", comments.get(0).text);
        assertEquals(129_000, comments.get(0).videoOffsetMs);
    }

    @Test
    public void splitsAListOfTimes() {
        List<NiconicoComment> comments = TimedComments.parseComment("id", "0:30 - intro\n1:05 - chorus", LENGTH_MS);

        assertEquals(2, comments.size());
        assertEquals("intro", comments.get(0).text);
        assertEquals(30_000, comments.get(0).videoOffsetMs);
        assertEquals("chorus", comments.get(1).text);
        assertEquals(65_000, comments.get(1).videoOffsetMs);
    }

    @Test
    public void usesTheWholeCommentWhenTheTimesHaveNoTextOfTheirOwn() {
        List<NiconicoComment> comments = TimedComments.parseComment("id", "1:00 and 1:30", LENGTH_MS);

        assertEquals(2, comments.size());
        assertEquals("and", comments.get(1).text);
    }

    @Test
    public void readsHoursAndIgnoresTimesAfterTheEnd() {
        List<NiconicoComment> hours = TimedComments.parseComment("id", "1:02:03 here", 2 * 3_600_000);
        assertEquals(3_723_000, hours.get(0).videoOffsetMs);

        assertTrue(TimedComments.parseComment("id", "5:00 too late", LENGTH_MS).isEmpty());
        assertTrue(TimedComments.parseComment("id", "no time here", LENGTH_MS).isEmpty());
        assertTrue(TimedComments.parseComment("id", "a ratio of 12:345", LENGTH_MS).isEmpty());
        assertTrue(TimedComments.parseComment("id", "1:75:00 not a time", 2 * 3_600_000).isEmpty());
    }

    @Test
    public void removesFullWidthSpacesAroundTheText() {
        List<NiconicoComment> comments = TimedComments.parseComment("id", "1:23　すごい　", LENGTH_MS);

        assertEquals(1, comments.size());
        assertEquals("すごい", comments.get(0).text);
    }

    @Test
    public void parsesTopLevelCommentsAndTheNextPage() throws Exception {
        JSONObject response = new JSONObject()
                .put("frameworkUpdates", new JSONObject().put("entityBatchUpdate", new JSONObject()
                        .put("mutations", new JSONArray()
                                .put(comment("a", 0, "2:12 best line"))
                                .put(comment("b", 1, "0:10 a reply"))
                                .put(comment("c", 0, "no time")))))
                .put("onResponseReceivedEndpoints", new JSONArray()
                        .put(new JSONObject().put("appendContinuationItemsAction", new JSONObject()
                                .put("continuationItems", new JSONArray()
                                        .put(new JSONObject().put("commentThreadRenderer", new JSONObject()))
                                        .put(continuationItem("next"))))));

        TimedComments.Page page = TimedComments.parsePage(response, LENGTH_MS);

        assertEquals(1, page.comments.size());
        assertEquals("best line", page.comments.get(0).text);
        assertEquals(132_000, page.comments.get(0).videoOffsetMs);
        assertEquals("next", page.continuation);
    }

    @Test
    public void findsTheCommentsSectionOfTheWatchPage() throws Exception {
        JSONObject initialData = new JSONObject().put("contents", new JSONObject()
                .put("twoColumnWatchNextResults", new JSONObject()
                        .put("results", new JSONObject().put("results", new JSONObject()
                                .put("contents", new JSONArray()
                                        .put(new JSONObject().put("videoPrimaryInfoRenderer", new JSONObject()))
                                        .put(new JSONObject().put("itemSectionRenderer", new JSONObject()
                                                .put("sectionIdentifier", "comment-item-section")
                                                .put("contents", new JSONArray().put(continuationItem("comments"))))))))));

        assertEquals("comments", TimedComments.findCommentsContinuation(initialData));
        assertNull(TimedComments.findCommentsContinuation(new JSONObject()));
    }

    private static JSONObject comment(String id, int replyLevel, String text) throws Exception {
        return new JSONObject().put("payload", new JSONObject().put("commentEntityPayload", new JSONObject()
                .put("properties", new JSONObject()
                        .put("commentId", id)
                        .put("replyLevel", replyLevel)
                        .put("content", new JSONObject().put("content", text)))));
    }

    private static JSONObject continuationItem(String token) throws Exception {
        return new JSONObject().put("continuationItemRenderer", new JSONObject()
                .put("continuationEndpoint", new JSONObject()
                        .put("continuationCommand", new JSONObject().put("token", token))));
    }
}

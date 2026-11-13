/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/** Unit tests for the pure JSON / string helpers of {@link LyricsRequests}. No Android context is needed. */
public class LyricsRequestsJsonTest {

    // ── optPath ──

    @Test
    public void optPathNestedExists() throws JSONException {
        JSONObject root = new JSONObject()
                .put("a", new JSONObject().put("b", new JSONObject().put("c", "value")));
        JSONObject result = LyricsRequests.optPath(root, "a", "b");
        assertNotNull(result);
        assertEquals("value", result.optString("c"));
    }

    @Test
    public void optPathMissingIntermediateKey() throws JSONException {
        JSONObject root = new JSONObject()
                .put("a", new JSONObject().put("x", "value"));
        assertNull(LyricsRequests.optPath(root, "a", "b", "c"));
    }

    @Test
    public void optPathMissingFirstKey() throws JSONException {
        JSONObject root = new JSONObject().put("a", new JSONObject());
        assertNull(LyricsRequests.optPath(root, "missing", "b"));
    }

    @Test
    public void optPathEmptyPath() throws JSONException {
        JSONObject root = new JSONObject().put("a", "value");
        JSONObject result = LyricsRequests.optPath(root);
        assertNotNull(result);
        assertEquals(root, result);
    }

    @Test
    public void optPathNullRoot() {
        assertNull(LyricsRequests.optPath(null, "a"));
    }

    // ── optString ──

    @Test
    public void optStringPresentTrimmed() throws JSONException {
        // trim() is only used to check for blankness; the original value is returned.
        JSONObject obj = new JSONObject().put("title", "  Hello  ");
        assertEquals("  Hello  ", LyricsRequests.optString(obj, "title"));
    }

    @Test
    public void optStringJsonNull() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("title", JSONObject.NULL);
        assertNull(LyricsRequests.optString(obj, "title"));
    }

    @Test
    public void optStringBlankString() throws JSONException {
        JSONObject obj = new JSONObject().put("title", "   ");
        assertNull(LyricsRequests.optString(obj, "title"));
    }

    @Test
    public void optStringMissingKey() throws JSONException {
        JSONObject obj = new JSONObject().put("other", "value");
        assertNull(LyricsRequests.optString(obj, "title"));
    }

    // ── stringList(JSONArray) ──

    @Test
    public void stringListArrayWithStrings() {
        JSONArray arr = new JSONArray().put("one").put("two").put("three");
        List<String> result = LyricsRequests.stringList(arr);
        assertEquals(List.of("one", "two", "three"), result);
    }

    @Test
    public void stringListArrayWithNullsAndBlanks() {
        JSONArray arr = new JSONArray()
                .put("one")
                .put(JSONObject.NULL)
                .put("  ")
                .put("two")
                .put("");
        List<String> result = LyricsRequests.stringList(arr);
        assertEquals(List.of("one", "two"), result);
    }

    @Test
    public void stringListEmptyArray() {
        JSONArray arr = new JSONArray();
        List<String> result = LyricsRequests.stringList(arr);
        assertTrue(result.isEmpty());
    }

    @Test
    public void stringListNullArray() {
        List<String> result = LyricsRequests.stringList(null);
        assertTrue(result.isEmpty());
    }

    // ── stringList(JSONObject, String) ──

    @Test
    public void stringListFromObjectKey() throws JSONException {
        JSONArray arr = new JSONArray().put("a").put("b");
        JSONObject obj = new JSONObject().put("tags", arr);
        List<String> result = LyricsRequests.stringList(obj, "tags");
        assertEquals(List.of("a", "b"), result);
    }

    @Test
    public void stringListFromObjectMissingKey() throws JSONException {
        JSONObject obj = new JSONObject().put("other", "value");
        List<String> result = LyricsRequests.stringList(obj, "tags");
        assertTrue(result.isEmpty());
    }

    // ── encode ──

    @Test
    public void encodeSimpleString() throws Exception {
        assertEquals("hello", LyricsRequests.encode("hello"));
    }

    @Test
    public void encodeWithSpaces() throws Exception {
        assertEquals("hello+world", LyricsRequests.encode("hello world"));
    }

    @Test
    public void encodeWithSpecialChars() throws Exception {
        assertEquals("a%2Bb%3Dc", LyricsRequests.encode("a+b=c"));
    }

    // ── parsePlainTextLines ──

    @Test
    public void parsePlainTextLinesMultiLine() {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines("Line one\nLine two\nLine three");
        assertEquals(3, lines.size());
        assertEquals("Line one", lines.get(0).text());
        assertEquals("Line two", lines.get(1).text());
        assertEquals("Line three", lines.get(2).text());
    }

    @Test
    public void parsePlainTextLinesWithBlankLines() {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines("Line one\n\n  \nLine two");
        assertEquals(2, lines.size());
        assertEquals("Line one", lines.get(0).text());
        assertEquals("Line two", lines.get(1).text());
    }

    @Test
    public void parsePlainTextLinesTrailingNewline() {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines("Line one\nLine two\n");
        assertEquals(2, lines.size());
    }

    @Test
    public void parsePlainTextLinesEmptyString() {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines("");
        assertTrue(lines.isEmpty());
    }

    @Test
    public void parsePlainTextLinesNull() {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines(null);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void parsePlainTextLinesHasNoTime() {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines("Hello");
        assertEquals(1, lines.size());
        assertEquals(LyricsLine.NO_TIME, lines.get(0).startTimeMs());
        assertEquals("Hello", lines.get(0).text());
    }

    // ── jaccard ──

    @Test
    public void jaccardIdenticalStrings() {
        assertEquals(1.0, LyricsRequests.jaccard("hello world", "hello world"), 0.001);
    }

    @Test
    public void jaccardCompletelyDifferent() {
        assertEquals(0.0, LyricsRequests.jaccard("hello", "world"), 0.001);
    }

    @Test
    public void jaccardPartialOverlap() {
        assertEquals(1.0 / 3.0, LyricsRequests.jaccard("hello world", "hello there"), 0.001);
    }

    @Test
    public void jaccardEmptyStrings() {
        assertEquals(0.0, LyricsRequests.jaccard("", ""), 0.001);
        assertEquals(0.0, LyricsRequests.jaccard("hello", ""), 0.001);
        assertEquals(0.0, LyricsRequests.jaccard("", "world"), 0.001);
    }

    @Test
    public void jaccardCaseInsensitive() {
        // Tokens are built from the raw string, so case must match.
        assertEquals(1.0, LyricsRequests.jaccard("Hello World", "Hello World"), 0.001);
    }

    @Test
    public void jaccardDisjointSingleToken() {
        assertEquals(0.0, LyricsRequests.jaccard("foo", "bar"), 0.001);
    }
}

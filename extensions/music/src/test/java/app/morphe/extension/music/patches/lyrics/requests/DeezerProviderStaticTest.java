/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Unit tests for the static helpers of {@link DeezerProvider}: flatten and validateArl. */
public class DeezerProviderStaticTest {

    // ── flatten ──

    @Test
    public void flattenStringReturnsTrimmed() {
        assertEquals("hello", DeezerProvider.flatten("  hello  "));
    }

    @Test
    public void flattenEmptyStringReturnsNull() {
        assertNull(DeezerProvider.flatten("   "));
    }

    @Test
    public void flattenNullReturnsNull() {
        assertNull(DeezerProvider.flatten(null));
    }

    @Test
    public void flattenJSONArrayJoinsWithComma() throws JSONException {
        JSONArray arr = new JSONArray().put("A").put("B").put("C");
        assertEquals("A, B, C", DeezerProvider.flatten(arr));
    }

    @Test
    public void flattenJSONArraySkipsEmptyEntries() throws JSONException {
        JSONArray arr = new JSONArray().put("A").put("").put("B").put("  ");
        assertEquals("A, B", DeezerProvider.flatten(arr));
    }

    @Test
    public void flattenJSONArrayAllEmptyReturnsNull() throws JSONException {
        JSONArray arr = new JSONArray().put("").put("  ");
        assertNull(DeezerProvider.flatten(arr));
    }

    @Test
    public void flattenJSONArrayEmptyReturnsNull() throws JSONException {
        assertNull(DeezerProvider.flatten(new JSONArray()));
    }

    @Test
    public void flattenOtherTypeReturnsNull() throws JSONException {
        assertNull(DeezerProvider.flatten(42));
        assertNull(DeezerProvider.flatten(new JSONObject()));
    }

    // ── validateArl ──

    @Test
    public void validateArlNullReturnsFalse() {
        assertFalse(DeezerProvider.validateArl(null));
    }

    @Test
    public void validateArlEmptyReturnsFalse() {
        assertFalse(DeezerProvider.validateArl(""));
    }

    @Test
    public void validateArlWhitespaceReturnsFalse() {
        assertFalse(DeezerProvider.validateArl("   "));
    }

    @Test
    public void validateArlLiteralNullReturnsFalse() {
        assertFalse(DeezerProvider.validateArl("null"));
    }
}

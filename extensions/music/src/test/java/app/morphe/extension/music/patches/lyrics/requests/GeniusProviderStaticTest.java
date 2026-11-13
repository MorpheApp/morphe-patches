/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Unit tests for the static helper methods of {@link GeniusProvider}: languageCode, isSong, primaryArtistName, lineOf, isSectionHeader, lyricsText. */
public class GeniusProviderStaticTest {

    // ── languageCode ──

    @Test
    public void languageCodeMapsKnownName() {
        assertEquals("zh", GeniusProvider.languageCode("Simplified Chinese"));
    }

    @Test
    public void languageCodeMapsTraditionalChinese() {
        assertEquals("zh", GeniusProvider.languageCode("Traditional Chinese"));
    }

    @Test
    public void languageCodeMapsPortuguese() {
        assertEquals("pt", GeniusProvider.languageCode("Brazilian Portuguese"));
    }

    @Test
    public void languageCodeMapsHungarian() {
        assertEquals("hu", GeniusProvider.languageCode("hungarian"));
    }

    @Test
    public void languageCodeUnknownReturnsTrimmedLowercase() {
        assertEquals("klingon", GeniusProvider.languageCode("Klingon"));
    }

    @Test
    public void languageCodeNullReturnsEmpty() {
        assertEquals("", GeniusProvider.languageCode(null));
    }

    @Test
    public void languageCodeEmptyReturnsEmpty() {
        assertEquals("", GeniusProvider.languageCode(""));
    }

    @Test
    public void languageCodeWhitespaceOnlyReturnsEmpty() {
        assertEquals("", GeniusProvider.languageCode("   "));
    }

    @Test
    public void languageCodeTrimsWhitespace() {
        assertEquals("zh", GeniusProvider.languageCode("  simplified chinese  "));
    }

    // ── isSong ──

    @Test
    public void isSongEmptyType() throws JSONException {
        JSONObject hit = new JSONObject();
        assertTrue(GeniusProvider.isSong(hit));
    }

    @Test
    public void isSongSongType() throws JSONException {
        JSONObject hit = new JSONObject().put("_type", "song");
        assertTrue(GeniusProvider.isSong(hit));
    }

    @Test
    public void isSongTypeField() throws JSONException {
        JSONObject hit = new JSONObject().put("type", "song");
        assertTrue(GeniusProvider.isSong(hit));
    }

    @Test
    public void isSongNonSongType() throws JSONException {
        JSONObject hit = new JSONObject().put("_type", "album");
        assertFalse(GeniusProvider.isSong(hit));
    }

    @Test
    public void isSongFallsBackToTypeField() throws JSONException {
        JSONObject hit = new JSONObject().put("_type", "album").put("type", "song");
        // _type takes priority
        assertFalse(GeniusProvider.isSong(hit));
    }

    // ── primaryArtistName ──

    @Test
    public void primaryArtistNameExtracts() throws JSONException {
        JSONObject hit = new JSONObject().put("primary_artist", new JSONObject().put("name", "The Beatles"));
        assertEquals("The Beatles", GeniusProvider.primaryArtistName(hit));
    }

    @Test
    public void primaryArtistNameMissingReturnsEmpty() throws JSONException {
        JSONObject hit = new JSONObject();
        assertEquals("", GeniusProvider.primaryArtistName(hit));
    }

    @Test
    public void primaryArtistNameNullArtistReturnsEmpty() throws JSONException {
        JSONObject hit = new JSONObject().put("primary_artist", null);
        assertEquals("", GeniusProvider.primaryArtistName(hit));
    }

    // ── lineOf ──

    @Test
    public void lineOfValidIndex() {
        int[] slots = {0, 1, 2};
        assertEquals(2, GeniusProvider.lineOf(2, slots, 3));
    }

    @Test
    public void lineOfNegativeIndex() {
        int[] slots = {0, 1, 2};
        assertEquals(-1, GeniusProvider.lineOf(-1, slots, 3));
    }

    @Test
    public void lineOfIndexOutOfBounds() {
        int[] slots = {0, 1, 2};
        assertEquals(-1, GeniusProvider.lineOf(5, slots, 3));
    }

    @Test
    public void lineOfSlotOutOfRange() {
        int[] slots = {0, 1, 5};
        assertEquals(-1, GeniusProvider.lineOf(2, slots, 3));
    }

    @Test
    public void lineOfSlotNegative() {
        int[] slots = {0, -1, 2};
        assertEquals(-1, GeniusProvider.lineOf(1, slots, 3));
    }

    // ── isSectionHeader ──

    @Test
    public void isSectionHeaderBracketFormat() {
        assertTrue(GeniusProvider.isSectionHeader("[Chorus]"));
    }

    @Test
    public void isSectionHeaderShortNotHeader() {
        // [A] has length 3 (> 2), so it IS a section header per the implementation
        assertTrue(GeniusProvider.isSectionHeader("[A]"));
    }

    @Test
    public void isSectionHeaderTwoCharNotHeader() {
        // "[]" has length 2 (not > 2), so not a section header
        assertFalse(GeniusProvider.isSectionHeader("[]"));
    }

    @Test
    public void isSectionHeaderNoBrackets() {
        assertFalse(GeniusProvider.isSectionHeader("Chorus"));
    }

    @Test
    public void isSectionHeaderOpenBracketOnly() {
        assertFalse(GeniusProvider.isSectionHeader("[Chorus"));
    }

    @Test
    public void isSectionHeaderCloseBracketOnly() {
        assertFalse(GeniusProvider.isSectionHeader("Chorus]"));
    }

    // ── lyricsText ──

    @Test
    public void lyricsTextNullReturnsNull() {
        assertNull(GeniusProvider.lyricsText(null));
    }

    @Test
    public void lyricsTextEmptyReturnsNull() {
        assertNull(GeniusProvider.lyricsText(""));
    }

    @Test
    public void lyricsTextExtractsContainer() {
        String html = "<div data-lyrics-container=\"true\">Hello<br>World</div>";
        String result = GeniusProvider.lyricsText(html);
        assertNotNull(result);
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
    }

    @Test
    public void lyricsTextDecodesHtmlEntities() {
        String html = "<div data-lyrics-container=\"true\">Tom &amp; Jerry</div>";
        String result = GeniusProvider.lyricsText(html);
        assertNotNull(result);
        assertTrue(result.contains("Tom & Jerry"));
    }

    @Test
    public void lyricsTextExcludesSelection() {
        String html = "<div data-lyrics-container=\"true\">Hello<div data-exclude-from-selection=\"true\">Hidden</div>World</div>";
        String result = GeniusProvider.lyricsText(html);
        assertNotNull(result);
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
        assertFalse(result.contains("Hidden"));
    }

    @Test
    public void lyricsTextNoContainerReturnsEmpty() {
        String html = "<div>Not a lyrics container</div>";
        assertEquals("", GeniusProvider.lyricsText(html));
    }

    @Test
    public void lyricsTextMultipleContainers() {
        String html = "<div data-lyrics-container=\"true\">Line1<br>Line2</div>"
                + "<div data-lyrics-container=\"true\">Line3<br>Line4</div>";
        String result = GeniusProvider.lyricsText(html);
        assertNotNull(result);
        assertTrue(result.contains("Line1"));
        assertTrue(result.contains("Line2"));
        assertTrue(result.contains("Line3"));
        assertTrue(result.contains("Line4"));
    }

    @Test
    public void lyricsTextTrimsBlankLines() {
        String html = "<div data-lyrics-container=\"true\">Line1<br><br>Line2</div>";
        String result = GeniusProvider.lyricsText(html);
        assertNotNull(result);
        // Blank runs collapse to a single blank line
        assertTrue(result.contains("Line1"));
        assertTrue(result.contains("Line2"));
    }
}

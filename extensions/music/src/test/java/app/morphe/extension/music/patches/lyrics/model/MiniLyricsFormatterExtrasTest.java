/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Tests for the secondary entry points of {@link MiniLyricsFormatter}: the variant placeholder check, the single-pass colon-segment renderer and the East Asian Width lookup. */
public class MiniLyricsFormatterExtrasTest {

    // ── usesVariant ──

    @Test
    public void usesVariantFallsBackToDefaultForNull() {
        assertTrue(MiniLyricsFormatter.usesVariant(null));
    }

    @Test
    public void usesVariantTrueForTemplateWithV() {
        assertTrue(MiniLyricsFormatter.usesVariant("{t} :({v}): - {a}"));
        assertTrue(MiniLyricsFormatter.usesVariant("{v} {t}"));
    }

    @Test
    public void usesVariantFalseForTemplateWithoutV() {
        assertFalse(MiniLyricsFormatter.usesVariant("{t} - {a}"));
        assertFalse(MiniLyricsFormatter.usesVariant("{a} - {t}"));
    }

    @Test
    public void usesVariantFalseForEmptyString() {
        assertFalse(MiniLyricsFormatter.usesVariant(""));
    }

    // ── render ──

    @Test
    public void renderSubstitutesPlaceholdersOutsideSegments() {
        Map<String, String> values = new HashMap<>();
        values.put("t", "Song");
        values.put("a", "Artist");
        assertEquals("Song - Artist", MiniLyricsFormatter.render("{t} - {a}", values, true));
    }

    @Test
    public void renderShowsBareSectionWhenBareShown() {
        assertEquals("bare section", MiniLyricsFormatter.render(":bare section:", new HashMap<>(), true));
    }

    @Test
    public void renderHidesBareSectionWhenNotBareShown() {
        assertEquals("", MiniLyricsFormatter.render(":bare section:", new HashMap<>(), false));
    }

    @Test
    public void renderHidesUnpopulatedPlaceholderSegment() {
        Map<String, String> values = new HashMap<>();
        values.put("t", "");
        assertEquals("", MiniLyricsFormatter.render(":{t}:", values, true));
    }

    @Test
    public void renderShowsPopulatedPlaceholderSegment() {
        Map<String, String> values = new HashMap<>();
        values.put("t", "value");
        assertEquals("value", MiniLyricsFormatter.render(":{t}:", values, true));
    }

    @Test
    public void renderEscapedColonIsLiteral() {
        assertEquals("a:b", MiniLyricsFormatter.render("a\\:b", new HashMap<>(), true));
    }

    @Test
    public void renderMultipleSegmentsMixed() {
        Map<String, String> values = new HashMap<>();
        values.put("t", "Song");
        values.put("a", "");
        assertEquals("Song ", MiniLyricsFormatter.render(":{t}: :{a}:", values, true));
    }

    // ── isFullWidth ──

    @Test
    public void isFullWidthFalseForAsciiLetter() {
        assertFalse(MiniLyricsFormatter.isFullWidth('A'));
        assertFalse(MiniLyricsFormatter.isFullWidth('z'));
        assertFalse(MiniLyricsFormatter.isFullWidth('0'));
        assertFalse(MiniLyricsFormatter.isFullWidth(' '));
    }

    @Test
    public void isFullWidthTrueForFullwidthChar() {
        assertTrue(MiniLyricsFormatter.isFullWidth('Ａ'));
        assertTrue(MiniLyricsFormatter.isFullWidth('あ'));
        assertTrue(MiniLyricsFormatter.isFullWidth('中'));
    }

    @Test
    public void isFullWidthBoundaryCodePoints() {
        assertTrue(MiniLyricsFormatter.isFullWidth(0x1100)); // range start
        assertTrue(MiniLyricsFormatter.isFullWidth(0x115F)); // range end
        assertFalse(MiniLyricsFormatter.isFullWidth(0x10FF)); // just before range start
        assertFalse(MiniLyricsFormatter.isFullWidth(0x1160)); // just after range end
        assertTrue(MiniLyricsFormatter.isFullWidth(0xFF01));
        assertTrue(MiniLyricsFormatter.isFullWidth(0xFF60));
        assertFalse(MiniLyricsFormatter.isFullWidth(0xFF00));
        assertFalse(MiniLyricsFormatter.isFullWidth(0xFF61));
    }
}

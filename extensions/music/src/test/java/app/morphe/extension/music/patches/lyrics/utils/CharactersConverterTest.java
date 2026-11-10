/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

/**
 * Under the mockable android.jar the ICU {@code Transliterator} stubs return null, so every
 * transliteration pass is a no-op and the text comes through unchanged. These tests pin that
 * fallback behavior: when the real ICU is present the transforms would differ, but the
 * no-transliterator path is what the unit-test JVM exercises.
 */
public class CharactersConverterTest {

    @Test
    public void toSimplifiedReturnsInputWhenTransliteratorIsNull() {
        assertEquals("中文测试", CharactersConverter.toSimplified("中文测试"));
    }

    @Test
    public void toTraditionalReturnsInputWhenTransliteratorIsNull() {
        assertEquals("中文测试", CharactersConverter.toTraditional("中文测试"));
    }

    @Test
    public void normalizeCollapsesWhitespaceWhenTransliteratorsAreNull() {
        assertEquals("hello world", CharactersConverter.normalize("  hello   world  "));
        assertEquals("HELLO", CharactersConverter.normalize("  HELLO  "));
    }

    @Test
    public void normalizePreserveCaseCollapsesWhitespace() {
        assertEquals("Hello World", CharactersConverter.normalizePreserveCase("  Hello   World  "));
    }

    @Test
    public void variantsOfStringIncludesOriginal() {
        List<String> variants = CharactersConverter.variants("hello");
        assertTrue(variants.contains("hello"));
        // With null transliterator all transforms collapse to the same string, so the set is just {hello}.
        assertEquals(1, variants.size());
    }

    @Test
    public void variantsOfStringWithWhitespaceAddsNormalizedForm() {
        List<String> variants = CharactersConverter.variants("  hello  ");
        assertTrue(variants.contains("  hello  "));
        assertTrue(variants.contains("hello"));
    }

    @Test
    public void variantsOfTrackEmptyWhenTransliteratorsAreNull() {
        TrackInfo track = new TrackInfo("Title", "Artist", null, 0);
        List<TrackInfo> variants = CharactersConverter.variants(track);
        // addVariant returns early when the Transliterator is null, so no variant is produced.
        assertTrue(variants.isEmpty());
    }

    @Test
    public void hasTextIsNonEmptyForVariants() {
        List<String> variants = CharactersConverter.variants("some text");
        assertFalse(variants.isEmpty());
    }
}

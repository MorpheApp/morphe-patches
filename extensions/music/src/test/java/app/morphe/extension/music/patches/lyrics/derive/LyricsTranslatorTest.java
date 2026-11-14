/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsTranslatorTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void sanitizeTranslationReplacesNullWithEmpty() throws Exception {
        // List.of() rejects null elements, so use Arrays.asList() to include null.
        List<String> result = callSanitizeTranslation(Arrays.asList("text", null, "more"));
        assertEquals("text", result.get(0));
        assertEquals("", result.get(1));
        assertEquals("more", result.get(2));
    }

    @Test
    public void sanitizeTranslationKeepsText() throws Exception {
        List<String> result = callSanitizeTranslation(List.of("hello", "world"));
        assertEquals("hello", result.get(0));
        assertEquals("world", result.get(1));
    }

    @Test
    public void sanitizeTranslationEmptyList() throws Exception {
        List<String> result = callSanitizeTranslation(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    public void hasTranslationTrueWhenDifferent() throws Exception {
        assertTrue(callHasTranslation(List.of("translated"), List.of("source")));
    }

    @Test
    public void hasTranslationFalseWhenSame() throws Exception {
        assertFalse(callHasTranslation(List.of("source"), List.of("source")));
    }

    @Test
    public void hasTranslationFalseWhenNull() throws Exception {
        assertFalse(callHasTranslation(null, List.of("source")));
    }

    @Test
    public void hasTranslationFalseWhenDifferentSize() throws Exception {
        assertFalse(callHasTranslation(List.of("translated"), List.of("source1", "source2")));
    }

    @Test
    public void hasTranslationFalseWhenEmpty() throws Exception {
        assertFalse(callHasTranslation(List.of(), List.of()));
    }

    @Test
    public void hasTranslationTrueWithTrimmedDifference() throws Exception {
        assertTrue(callHasTranslation(List.of("translated"), List.of("source")));
    }

    @Test
    public void hasTranslationFalseWhenOnlyEmptyLines() throws Exception {
        List<String> translated = List.of("", "");
        List<String> source = List.of("source1", "source2");
        assertFalse(callHasTranslation(translated, source));
    }

    @Test
    public void hasTranslationTrueWhenOneLineDiffers() throws Exception {
        List<String> translated = List.of("source1", "translated2");
        List<String> source = List.of("source1", "source2");
        assertTrue(callHasTranslation(translated, source));
    }

    @Test
    public void embeddedTranslationNullWhenNoTranslations() throws Exception {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "hello"));
        Lyrics lyrics = new Lyrics(lines, "provider", true, null, null, null, null, null, null, null);
        assertNull(callEmbeddedTranslation(lyrics, "en", 1));
    }

    @Test
    public void embeddedTranslationNullWhenEmptyTranslations() throws Exception {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "hello"));
        Lyrics lyrics = new Lyrics(lines, "provider", true, null, java.util.Map.of(), null, null, null, null, null);
        assertNull(callEmbeddedTranslation(lyrics, "en", 1));
    }

    @SuppressWarnings("unchecked")
    private static List<String> callSanitizeTranslation(List<String> lines) throws Exception {
        Method method = LyricsTranslator.class.getDeclaredMethod("sanitizeTranslation", List.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, lines);
    }

    @SuppressWarnings("unchecked")
    private static boolean callHasTranslation(List<String> translated, List<String> source) throws Exception {
        Method method = LyricsTranslator.class.getDeclaredMethod("hasTranslation", List.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, translated, source);
    }

    @SuppressWarnings("unchecked")
    private static List<String> callEmbeddedTranslation(Lyrics lyrics, String target, int lineCount)
            throws Exception {
        Method method = LyricsTranslator.class.getDeclaredMethod("embeddedTranslation", Lyrics.class, String.class, int.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, lyrics, target, lineCount);
    }
}

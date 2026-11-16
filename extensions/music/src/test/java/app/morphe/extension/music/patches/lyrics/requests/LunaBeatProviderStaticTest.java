/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the static normalization helper of {@link LunaBeatProvider}: normalizeLunabeat. */
public class LunaBeatProviderStaticTest {

    @Test
    public void normalizeLunabeatLowercases() {
        assertEquals("hello", LunaBeatProvider.normalizeLunabeat("HELLO"));
    }

    @Test
    public void normalizeLunabeatNfkcFoldsFullwidth() {
        // Fullwidth letters fold to ASCII under NFKC
        assertEquals("abc", LunaBeatProvider.normalizeLunabeat("ａｂｃ"));
    }

    @Test
    public void normalizeLunabeatRemovesSpaces() {
        assertEquals("helloworld", LunaBeatProvider.normalizeLunabeat("hello world"));
    }

    @Test
    public void normalizeLunabeatRemovesMiddleDots() {
        assertEquals("helloworld", LunaBeatProvider.normalizeLunabeat("hello·world"));
    }

    @Test
    public void normalizeLunabeatRemovesKatakanaMiddleDot() {
        assertEquals("helloworld", LunaBeatProvider.normalizeLunabeat("hello・world"));
    }

    @Test
    public void normalizeLunabeatRemovesPeriods() {
        assertEquals("helloworld", LunaBeatProvider.normalizeLunabeat("hello.world"));
    }

    @Test
    public void normalizeLunabeatRemovesHyphens() {
        assertEquals("helloworld", LunaBeatProvider.normalizeLunabeat("hello-world"));
    }

    @Test
    public void normalizeLunabeatRemovesUnderscores() {
        assertEquals("helloworld", LunaBeatProvider.normalizeLunabeat("hello_world"));
    }

    @Test
    public void normalizeLunabeatRemovesAllTargetChars() {
        assertEquals("abc", LunaBeatProvider.normalizeLunabeat(" a-b.c·・_"));
    }

    @Test
    public void normalizeLunabeatNullReturnsEmpty() {
        assertEquals("", LunaBeatProvider.normalizeLunabeat(null));
    }

    @Test
    public void normalizeLunabeatEmptyReturnsEmpty() {
        assertEquals("", LunaBeatProvider.normalizeLunabeat(""));
    }

    @Test
    public void normalizeLunabeatPreservesCjk() {
        assertEquals("你好世界", LunaBeatProvider.normalizeLunabeat("你好 世界"));
    }

    @Test
    public void normalizeLunabeatMixedCjkAndLatin() {
        assertEquals("hello世界", LunaBeatProvider.normalizeLunabeat("Hello 世界"));
    }
}

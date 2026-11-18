/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsFilterDefaults;

/** Unit tests for the package-private helper methods of {@link LyricsRequests}: splitArtistHits, foldPunctuation, stripDecorations, declaredScript, regionSubtag, chineseScriptOfRegion, isScriptSpelling, tokens. */
public class LyricsRequestsHelpersTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    // ── splitArtistHits ──

    @Test
    public void splitArtistHitsExactPart() {
        assertTrue(LyricsRequests.splitArtistHits("John & Paul", "John"));
    }

    @Test
    public void splitArtistHitsFeatSeparator() {
        assertTrue(LyricsRequests.splitArtistHits("ArtistA feat. ArtistB", "ArtistB"));
    }

    @Test
    public void splitArtistHitsFtSeparator() {
        assertTrue(LyricsRequests.splitArtistHits("ArtistA ft ArtistB", "ArtistB"));
    }

    @Test
    public void splitArtistHitsWithSeparator() {
        assertTrue(LyricsRequests.splitArtistHits("ArtistA with ArtistB", "ArtistB"));
    }

    @Test
    public void splitArtistHitsCommaSeparator() {
        assertTrue(LyricsRequests.splitArtistHits("ArtistA, ArtistB", "ArtistB"));
    }

    @Test
    public void splitArtistHitsSlashSeparator() {
        assertTrue(LyricsRequests.splitArtistHits("ArtistA / ArtistB", "ArtistB"));
    }

    @Test
    public void splitArtistHitsMultiByteSeparator() {
        assertTrue(LyricsRequests.splitArtistHits("ArtistA × ArtistB", "ArtistB"));
    }

    @Test
    public void splitArtistHitsNoMatch() {
        assertFalse(LyricsRequests.splitArtistHits("John Doe", "Unknown"));
    }

    @Test
    public void splitArtistHitsEmptyCandidate() {
        assertFalse(LyricsRequests.splitArtistHits("", "John"));
    }

    @Test
    public void splitArtistHitsPartialShortMatch() {
        // Short non-CJK parts need the longer length tolerance
        assertTrue(LyricsRequests.splitArtistHits("AB & CD", "AB"));
    }

    // ── foldPunctuation ──

    @Test
    public void foldPunctuationFoldsFullwidthExclamation() {
        assertEquals("!", LyricsRequests.foldPunctuation("！"));
    }

    @Test
    public void foldPunctuationFoldsFullwidthComma() {
        assertEquals(",", LyricsRequests.foldPunctuation("，"));
    }

    @Test
    public void foldPunctuationFoldsFullwidthPeriod() {
        assertEquals(".", LyricsRequests.foldPunctuation("．"));
    }

    @Test
    public void foldPunctuationRemovesInterpunct() {
        assertEquals("helloworld", LyricsRequests.foldPunctuation("hello·world"));
    }

    @Test
    public void foldPunctuationRemovesBullet() {
        assertEquals("helloworld", LyricsRequests.foldPunctuation("hello•world"));
    }

    @Test
    public void foldPunctuationRemovesKatakanaMiddleDot() {
        assertEquals("helloworld", LyricsRequests.foldPunctuation("hello・world"));
    }

    @Test
    public void foldPunctuationLeavesAscii() {
        assertEquals("hello, world!", LyricsRequests.foldPunctuation("hello, world!"));
    }

    @Test
    public void foldPunctuationLeavesCjk() {
        // Fullwidth comma U+FF0C is in the fold range 0xFF01-0xFF5E, so it folds to ASCII
        assertEquals("你好,世界", LyricsRequests.foldPunctuation("你好，世界"));
    }

    // ── stripDecorations ──

    @Test
    public void stripDecorationsEmptyRegex() {
        LyricsFilterDefaults.LYRICS_CUSTOM_REGEX.save("");
        assertEquals("Hello World", LyricsRequests.stripDecorations("Hello World"));
    }

    @Test
    public void stripDecorationsRemovesMatching() {
        LyricsFilterDefaults.LYRICS_CUSTOM_REGEX.save("\\(.*?\\)");
        assertEquals("Hello  World", LyricsRequests.stripDecorations("Hello (Live) World"));
    }

    @Test
    public void stripDecorationsInvalidRegexKeepsText() {
        LyricsFilterDefaults.LYRICS_CUSTOM_REGEX.save("[invalid");
        assertEquals("Hello (Live)", LyricsRequests.stripDecorations("Hello (Live)"));
    }

    // ── declaredScript ──

    @Test
    public void declaredScriptFindsFourLetterScript() {
        assertEquals("Hant", LyricsRequests.declaredScript("zh-Hant-TW"));
    }

    @Test
    public void declaredScriptCaseInsensitive() {
        assertEquals("Hant", LyricsRequests.declaredScript("zh-hant"));
    }

    @Test
    public void declaredScriptNullReturnsEmpty() {
        assertEquals("", LyricsRequests.declaredScript(null));
    }

    @Test
    public void declaredScriptNoScriptReturnsEmpty() {
        assertEquals("", LyricsRequests.declaredScript("en-US"));
    }

    @Test
    public void declaredScriptUnderscoreSeparator() {
        assertEquals("Hant", LyricsRequests.declaredScript("zh_Hant"));
    }

    // ── regionSubtag ──

    @Test
    public void regionSubtagFindsTwoLetterRegion() {
        assertEquals("TW", LyricsRequests.regionSubtag("zh-Hant-TW"));
    }

    @Test
    public void regionSubtagNullReturnsEmpty() {
        assertEquals("", LyricsRequests.regionSubtag(null));
    }

    @Test
    public void regionSubtagNoRegionReturnsEmpty() {
        assertEquals("", LyricsRequests.regionSubtag("zh-Hant"));
    }

    @Test
    public void regionSubtagUnderscoreSeparator() {
        assertEquals("TW", LyricsRequests.regionSubtag("zh_Hant_TW"));
    }

    // ── chineseScriptOfRegion ──

    @Test
    public void chineseScriptOfRegionTaiwan() {
        assertEquals("Hant", LyricsRequests.chineseScriptOfRegion("TW"));
    }

    @Test
    public void chineseScriptOfRegionHongKong() {
        assertEquals("Hant", LyricsRequests.chineseScriptOfRegion("HK"));
    }

    @Test
    public void chineseScriptOfRegionMacau() {
        assertEquals("Hant", LyricsRequests.chineseScriptOfRegion("MO"));
    }

    @Test
    public void chineseScriptOfRegionChina() {
        assertEquals("Hans", LyricsRequests.chineseScriptOfRegion("CN"));
    }

    @Test
    public void chineseScriptOfRegionSingapore() {
        assertEquals("Hans", LyricsRequests.chineseScriptOfRegion("SG"));
    }

    @Test
    public void chineseScriptOfRegionMalaysia() {
        assertEquals("Hans", LyricsRequests.chineseScriptOfRegion("MY"));
    }

    @Test
    public void chineseScriptOfRegionUnknownReturnsEmpty() {
        assertEquals("", LyricsRequests.chineseScriptOfRegion("US"));
    }

    @Test
    public void chineseScriptOfRegionNullReturnsEmpty() {
        assertEquals("", LyricsRequests.chineseScriptOfRegion(null));
    }

    // ── isScriptSpelling ──

    @Test
    public void isScriptSpellingTrueForLetters() {
        assertTrue(LyricsRequests.isScriptSpelling("Hant"));
    }

    @Test
    public void isScriptSpellingFalseForDigits() {
        assertFalse(LyricsRequests.isScriptSpelling("H1nt"));
    }

    @Test
    public void isScriptSpellingFalseForHyphen() {
        assertFalse(LyricsRequests.isScriptSpelling("Han-t"));
    }

    // ── tokens ──

    @Test
    public void tokensSimpleWords() {
        Set<String> result = LyricsRequests.tokens("hello world");
        assertEquals(Set.of("hello", "world"), result);
    }

    @Test
    public void tokensWithPunctuation() {
        Set<String> result = LyricsRequests.tokens("hello, world!");
        assertEquals(Set.of("hello", "world"), result);
    }

    @Test
    public void tokensMixedCjkAndLatin() {
        Set<String> result = LyricsRequests.tokens("你好 hello 世界");
        assertEquals(Set.of("你好", "hello", "世界"), result);
    }

    @Test
    public void tokensEmptyString() {
        assertTrue(LyricsRequests.tokens("").isEmpty());
    }

    @Test
    public void tokensAllPunctuation() {
        assertTrue(LyricsRequests.tokens("!!!,,,").isEmpty());
    }

    @Test
    public void tokensPreservesOrder() {
        Set<String> result = LyricsRequests.tokens("b a c");
        assertEquals(List.of("b", "a", "c"), List.copyOf(result));
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Unit tests for the static string helpers of {@link LRCRedProvider}: normalize, cleanBaseTitle, extractArtists. */
public class LRCRedProviderStaticTest {

    // ── normalize ──

    @Test
    public void normalizeLowercases() {
        assertEquals("hello world", LRCRedProvider.normalize("HELLO WORLD"));
    }

    @Test
    public void normalizeReplacesNonLetters() {
        assertEquals("hello world", LRCRedProvider.normalize("hello, world!"));
    }

    @Test
    public void normalizeCollapsesWhitespace() {
        assertEquals("hello world", LRCRedProvider.normalize("hello    world"));
    }

    @Test
    public void normalizeNullReturnsEmpty() {
        assertEquals("", LRCRedProvider.normalize(null));
    }

    @Test
    public void normalizeEmptyReturnsEmpty() {
        assertEquals("", LRCRedProvider.normalize(""));
    }

    @Test
    public void normalizePreservesLettersAndDigits() {
        // Hyphens become spaces, then whitespace collapses
        assertEquals("a b c 1 2 3", LRCRedProvider.normalize("a-b-c 1-2-3"));
    }

    @Test
    public void normalizePreservesUnicodeLetters() {
        assertEquals("你好 world", LRCRedProvider.normalize("你好, world!"));
    }

    // ── cleanBaseTitle ──

    @Test
    public void cleanBaseTitleRemovesBracketedTags() {
        // "feat." matches BRACKETED_TAGS, so it gets removed before normalize
        assertEquals("hello world", LRCRedProvider.cleanBaseTitle("Hello World (feat. Someone)"));
    }

    @Test
    public void cleanBaseTitleRemovesFeatPattern() {
        assertEquals("hello world", LRCRedProvider.cleanBaseTitle("Hello World [feat. Artist]"));
    }

    @Test
    public void cleanBaseTitleRemovesVideoTags() {
        assertEquals("hello", LRCRedProvider.cleanBaseTitle("Hello [Official Video]"));
    }

    @Test
    public void cleanBaseTitleNullReturnsEmpty() {
        assertEquals("", LRCRedProvider.cleanBaseTitle(null));
    }

    @Test
    public void cleanBaseTitleAppliesNormalize() {
        // "(Live)" is not in the tag patterns, but normalize folds parens to spaces
        assertEquals("hello world live", LRCRedProvider.cleanBaseTitle("HELLO, WORLD (Live)!"));
    }

    @Test
    public void cleanBaseTitleNoTagsUnchanged() {
        assertEquals("hello world", LRCRedProvider.cleanBaseTitle("Hello World"));
    }

    // ── extractArtists ──

    @Test
    public void extractArtistsCommaSeparated() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA, ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsAmpersand() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA & ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsSlash() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA / ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsFeat() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA feat. ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsFt() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA ft ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsWith() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA with ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsX() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA x ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsSingle() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA");
        assertEquals(List.of("artista"), result);
    }

    @Test
    public void extractArtistsNullReturnsEmpty() {
        assertTrue(LRCRedProvider.extractArtists(null).isEmpty());
    }

    @Test
    public void extractArtistsEmptyReturnsEmpty() {
        assertTrue(LRCRedProvider.extractArtists("").isEmpty());
    }

    @Test
    public void extractArtistsSkipsEmptyParts() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA,, ArtistB");
        assertEquals(List.of("artista", "artistb"), result);
    }

    @Test
    public void extractArtistsMixedSeparators() {
        List<String> result = LRCRedProvider.extractArtists("ArtistA, ArtistB feat. ArtistC");
        assertEquals(List.of("artista", "artistb", "artistc"), result);
    }
}

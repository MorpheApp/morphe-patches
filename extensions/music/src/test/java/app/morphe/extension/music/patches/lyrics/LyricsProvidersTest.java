/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;

public class LyricsProvidersTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static LyricsProvider namedProvider(String name) {
        return new LyricsProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public FetchResult fetch(TrackInfo track) {
                return null;
            }
        };
    }

    @Test
    public void searchableFiltersYtMusic() {
        List<LyricsProvider> result = LyricsProviders.searchableProviders(
                List.of(namedProvider("YTMusic")));
        assertTrue(result.isEmpty());
    }

    @Test
    public void searchableFiltersCaptions() {
        List<LyricsProvider> result = LyricsProviders.searchableProviders(
                List.of(namedProvider("Captions")));
        assertTrue(result.isEmpty());
    }

    @Test
    public void searchableFiltersUnison() {
        List<LyricsProvider> result = LyricsProviders.searchableProviders(
                List.of(namedProvider("Unison")));
        assertTrue(result.isEmpty());
    }

    @Test
    public void searchableFiltersSimpMusic() {
        List<LyricsProvider> result = LyricsProviders.searchableProviders(
                List.of(namedProvider("SimpMusic")));
        assertTrue(result.isEmpty());
    }

    @Test
    public void searchableKeepsOtherProviders() {
        List<LyricsProvider> result = LyricsProviders.searchableProviders(
                List.of(namedProvider("QQ"), namedProvider("LRCLIB")));
        assertEquals(2, result.size());
    }

    @Test
    public void searchableMixedFiltersCorrectly() {
        List<LyricsProvider> input = List.of(
                namedProvider("YTMusic"),
                namedProvider("QQ"),
                namedProvider("Captions"),
                namedProvider("LRCLIB"),
                namedProvider("SimpMusic"));
        List<LyricsProvider> result = LyricsProviders.searchableProviders(input);
        assertEquals(2, result.size());
        assertEquals("QQ", result.get(0).name());
        assertEquals("LRCLIB", result.get(1).name());
    }

    @Test
    public void searchableEmptyListReturnsEmpty() {
        assertTrue(LyricsProviders.searchableProviders(List.of()).isEmpty());
    }

    @Test
    public void isProviderEnabledYtMusicDefault() {
        // YTMusic is enabled in the default order (no "-" prefix).
        assertTrue(LyricsProviders.isProviderEnabled("YTMusic"));
    }

    @Test
    public void isProviderEnabledAppleDefault() {
        assertTrue(LyricsProviders.isProviderEnabled("Apple"));
    }

    @Test
    public void isProviderEnabledCaptionsDefault() {
        // Captions is disabled in the default order ("-Captions").
        assertFalse(LyricsProviders.isProviderEnabled("Captions"));
    }

    @Test
    public void isProviderEnabledPetitLyricsDefault() {
        assertFalse(LyricsProviders.isProviderEnabled("PetitLyrics"));
    }

    @Test
    public void isProviderEnabledUnknownProvider() {
        assertFalse(LyricsProviders.isProviderEnabled("UnknownProvider"));
    }

    @Test
    public void isProviderEnabledLRCLIBDefault() {
        assertTrue(LyricsProviders.isProviderEnabled("LRCLIB"));
    }

    @Test
    public void isProviderEnabledQQDefault() {
        assertTrue(LyricsProviders.isProviderEnabled("QQ"));
    }

    @Test
    public void allProvidersDisabledReturnsEmpty() {
        StringBuilder order = new StringBuilder();
        for (String id : LyricsProviders.PROVIDER_ORDER) {
            order.append('-').append(id).append(',');
        }
        assertTrue(LyricsProviders.providersInOrder(order.toString()).isEmpty());
    }
}

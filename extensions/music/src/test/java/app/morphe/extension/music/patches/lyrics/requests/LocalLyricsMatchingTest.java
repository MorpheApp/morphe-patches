/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;

import android.os.Build;

import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.utils.CharactersConverter;

public class LocalLyricsMatchingTest {
    @BeforeClass
    public static void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void sleepDoesNotUseGoToSleepByAnotherArtist() {
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", "Go To Sleep", "Eminem, DMX, Obie Trice",
                283, 282072, "My Chemical Romance"));
    }

    @Test
    public void substringTitleDoesNotMatchEvenWithTheSameArtist() {
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", "Go To Sleep", "My Chemical Romance",
                283, 283000, null));
    }

    @Test
    public void exactTitleDoesNotMatchAnotherArtist() {
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", "Sleep", "Eminem",
                283, 283000, null));
    }

    @Test
    public void artistSubstringIsNotAnIdentityMatch() {
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", "Sleep", "My Chemical Romance Tribute Band",
                283, 283000, null));
    }

    @Test
    public void unknownArtistCannotIdentifyAFile() {
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", "Sleep", null, 283, 283000, null));
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "", "Sleep", "My Chemical Romance", 283, 283000, null));
    }

    @Test
    public void missingTitleCannotIdentifyAFile() {
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", null, "My Chemical Romance", 283, 283000, null));
    }

    @Test
    public void cleanedPrimaryArtistMatchesTheExactRawCollaboration() {
        String rawTitle = "Go To Sleep";
        String rawArtist = "Eminem, DMX, Obie Trice";
        // Same preparation as LyricsManager.loadTrackOf, not a full list as the primary artist.
        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(rawTitle, rawArtist);
        TrackInfo track = new TrackInfo(parsed[1], parsed[0], "", 283);
        assertEquals("Eminem", track.artist());
        assertEquals(928, LocalLyricsFetcher.scoreMediaStoreCandidate(
                track.title(), track.artist(), rawTitle, rawArtist,
                track.durationSeconds(), 282072, rawArtist));
    }

    @Test
    public void cleanedPrimaryArtistDoesNotMatchADifferentCollaboration() {
        String rawArtist = "Eminem, DMX, Obie Trice";
        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist("Go To Sleep", rawArtist);
        assertEquals("Eminem", parsed[0]);
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                parsed[1], parsed[0], "Go To Sleep", "Eminem, Someone Else",
                283, 283000, rawArtist));
        assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                parsed[1], parsed[0], "Go To Sleep", rawArtist + " Tribute Band",
                283, 283000, rawArtist));
    }

    @Test
    public void caseOnlyIdentityMatchesWithoutIcuOnSupportedPre29Sdks() throws Exception {
        final int originalSdk = Build.VERSION.SDK_INT;
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (int sdk = 26; sdk <= 28; sdk++) {
                setSdkInt(sdk);
                assertEquals(sdk, Build.VERSION.SDK_INT);
                // Exercise the real converter's pre-29 no-transform path, not an ICU test double.
                assertEquals("Sleep", CharactersConverter.normalize("Sleep"));
                assertEquals("MY CHEMICAL ROMANCE", CharactersConverter.normalize("MY CHEMICAL ROMANCE"));
                assertEquals("API " + sdk, 0, LocalLyricsFetcher.scoreMediaStoreCandidate(
                        "Sleep", "MY CHEMICAL ROMANCE", "sleep", "my chemical romance",
                        283, 283000, null));
                assertEquals(Long.MAX_VALUE, LocalLyricsFetcher.scoreMediaStoreCandidate(
                        "Sleep", "MY CHEMICAL ROMANCE", "go to sleep", "my chemical romance",
                        283, 283000, null));
            }
        } finally {
            setSdkInt(originalSdk);
            Locale.setDefault(originalLocale);
        }
    }

    /** Set the mockable Android runtime's final SDK field without replacing production classes. */
    private static void setSdkInt(int sdk) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Field sdkField = Build.VERSION.class.getField("SDK_INT");
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, sdkField);
        final long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class)
                .invoke(unsafe, sdkField);
        unsafeClass.getMethod("putIntVolatile", Object.class, long.class, int.class)
                .invoke(unsafe, base, offset, sdk);
    }

    @Test
    public void matchingIdentityDoesNotRequireKnownDuration() {
        assertEquals(0, LocalLyricsFetcher.scoreMediaStoreCandidate(
                "Sleep", "My Chemical Romance", "Sleep", "My Chemical Romance", 0, 283000, null));
    }

    @Test
    public void normalizesWhitespaceBeforeMatchingIdentity() {
        assertEquals(0, LocalLyricsFetcher.scoreMediaStoreCandidate(
                " Sleep ", "My Chemical Romance", "Sleep", "My  Chemical Romance", 283, 283000, null));
    }
}

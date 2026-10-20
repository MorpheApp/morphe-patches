/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.List;

/** Preferred Caption Language only selects tracks already present in the menu. */
public class PreferredCaptionRoutingTest {
    private static final class Track implements PreferredCaptionLanguagePatch.CaptionTrackInterface {
        final String language;
        final String vss;
        Track(String language, String vss) {
            this.language = language;
            this.vss = vss;
        }
        @Override public String patch_getLanguageCode() { return language; }
        @Override public String patch_getVssId() { return vss; }
    }

    @Test public void selectsAnAlreadyExpandedTranslationWithoutCreatingOne() {
        Track english = new Track("en", "ta.ar.en");
        Track turkish = new Track("tr", "ta.ar.tr");
        assertSame(turkish, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(english, turkish), "tr"));
    }

    @Test public void exactRegionalTranslationWinsOverGeneric() {
        Track generic = new Track("pt", "ta.en.pt");
        Track regional = new Track("pt-PT", "ta.en.pt-PT");
        assertSame(regional, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(generic, regional), "pt-PT"));
        assertSame(generic, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(generic), "pt-PT"));
    }

    @Test public void missingTargetDoesNotSelectAnotherLanguageOrCreateNewTrack() {
        Track french = new Track("fr", "ta.en.fr");
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(french), "tr"));
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(), "tr"));
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(null, "tr"));
    }

    @Test public void simplifiedAndTraditionalChineseNeverCrossMatch() {
        Track traditional = new Track("zh-Hant", "ta.en.zh-Hant");
        Track simplified = new Track("zh-Hans", "ta.en.zh-Hans");
        assertSame(simplified, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(traditional, simplified), "zh-Hans"));
        assertSame(traditional, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(simplified, traditional), "zh-Hant"));
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(simplified), "zh-Hant"));
    }

    @Test public void matchesAlreadyAvailableLegacyLanguageCode() {
        Track hebrew = new Track("iw", "ta.en.iw");
        assertSame(hebrew, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(hebrew), "he"));
    }
}

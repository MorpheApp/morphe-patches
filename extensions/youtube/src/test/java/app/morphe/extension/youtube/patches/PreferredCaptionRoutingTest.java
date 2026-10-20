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
import java.util.Locale;

/** Selection of an auto-translated track that is already in the menu. */
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

    /** No direct tracks, so a missing translation cannot be created from a source track. */
    private static PreferredCaptionLanguagePatch.CaptionTrackInterface find(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> translated, String target) {
        return PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(), translated, null, target, Locale.ENGLISH);
    }

    @Test public void selectsAnExistingTranslation() {
        Track english = new Track("en", "ta.ar.en");
        Track turkish = new Track("tr", "ta.ar.tr");
        assertSame(turkish, find(List.of(english, turkish), "tr"));
    }

    @Test public void exactRegionalTranslationWinsOverGeneric() {
        Track generic = new Track("pt", "ta.en.pt");
        Track regional = new Track("pt-PT", "ta.en.pt-PT");
        assertSame(regional, find(List.of(generic, regional), "pt-PT"));
        assertSame(generic, find(List.of(generic), "pt-PT"));
    }

    @Test public void missingTargetDoesNotSelectAnotherLanguage() {
        Track french = new Track("fr", "ta.en.fr");
        assertNull(find(List.of(french), "tr"));
        assertNull(find(List.of(), "tr"));
        assertNull(find(null, "tr"));
    }

    @Test public void simplifiedAndTraditionalChineseNeverCrossMatch() {
        Track traditional = new Track("zh-Hant", "ta.en.zh-Hant");
        Track simplified = new Track("zh-Hans", "ta.en.zh-Hans");
        assertSame(simplified, find(List.of(traditional, simplified), "zh-Hans"));
        assertSame(traditional, find(List.of(simplified, traditional), "zh-Hant"));
        assertNull(find(List.of(simplified), "zh-Hant"));
    }

    @Test public void matchesLegacyLanguageCode() {
        Track hebrew = new Track("iw", "ta.en.iw");
        assertSame(hebrew, find(List.of(hebrew), "he"));
    }
}

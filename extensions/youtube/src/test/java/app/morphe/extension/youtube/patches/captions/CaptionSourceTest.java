/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

public class CaptionSourceTest {
    private static final class Track implements CaptionTranslationList.CaptionTrack {
        final String lang, url, vss;
        final CharSequence label;
        Track(String lang, String url, String vss, CharSequence label) {
            this.lang = lang; this.url = url; this.vss = vss; this.label = label;
        }
        public String universalCaptions_getLanguageCode() { return lang; }
        public String universalCaptions_getVssId() { return vss; }
        public String universalCaptions_getCaptionUrl() { return url; }
        public CharSequence universalCaptions_getCaptionLabel() { return label; }
        public CaptionTranslationList.CaptionTrack universalCaptions_copyTranslation(
                String lang, String url, String vss, CharSequence label) {
            return new Track(lang, url, vss, label);
        }
    }
    /** Exercise the source validation and generation API together. */
    private static CaptionTranslationList.CaptionTrack preview(
            CaptionTranslationList.CaptionTrack track, String language, Locale locale) {
        OriginalCaptionSource validated = OriginalCaptionSource.from(track);
        return validated == null ? null : validated.translate(language, locale);
    }

    private static final String URL =
            "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=ar&kind=asr&sig=a%2Bb";

    @Test public void canDeriveTurkishFromArabicAsrWithoutExistingTranslatedTrack() {
        Track arabic = new Track("ar", URL, "a.ar", "Arabic (auto-generated)");
        CaptionTranslationList.CaptionTrack result =
                preview(arabic, "tr", Locale.forLanguageTag("tr"));
        assertNotNull(result);
        assertEquals(URL + "&tlang=tr", result.universalCaptions_getCaptionUrl());
        assertEquals("ta.ar.tr", result.universalCaptions_getVssId());
        assertEquals("Türkçe", result.universalCaptions_getCaptionLabel());
        assertEquals(URL, arabic.url);
    }

    @Test public void rejectsTranslatedAndPseudoTracks() {
        assertNull(preview(
                new Track("de", URL + "&tlang=de", "ta.ar.de", "German"), "tr", Locale.ENGLISH));
        assertNull(preview(
                new Track("AUTO_TRANSLATE_CAPTIONS_OPTION", URL, "-", "Auto translate"),
                "tr", Locale.ENGLISH));
    }

    @Test public void rejectsUnsafeAndNonNativeUrls() {
        for (String url : new String[]{
                "http://www.youtube.com/api/timedtext?v=x&lang=ar",
                "https://youtube.com.evil.example/api/timedtext?v=x&lang=ar",
                "https://www.youtube.com/other?v=x&lang=ar",
                URL + "&tlang=fr"
        }) {
            assertNull(preview(
                    new Track("ar", url, "a.ar", "Arabic"), "tr", Locale.ENGLISH));
        }
    }
    @Test public void namedEnglishTrackKeepsItsFullSourceIdentityAndEncodedName() {
        String url = "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&name=English%20-%20896wrPZbXJE&sig=a%2Bb";
        Track source = new Track("en", url, ".en.78oJUrdTLnk", "English - 896wrPZbXJE");
        CaptionTranslationList.CaptionTrack translated = preview(source, "tr", Locale.ENGLISH);
        assertNotNull(translated);
        assertEquals(url + "&tlang=tr", translated.universalCaptions_getCaptionUrl());
        assertEquals("t.en.78oJUrdTLnk.tr", translated.universalCaptions_getVssId());
        assertEquals("Turkish", translated.universalCaptions_getCaptionLabel());
        assertEquals(url, source.url);
        assertEquals(".en.78oJUrdTLnk", source.vss);
    }

    @Test public void originalLanguageAliasesAreNotOfferedAsTranslations() {
        Track source = new Track("iw", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=iw", ".iw", "Hebrew");
        assertNull(preview(source, "he", Locale.ENGLISH));
    }

    @Test public void rejectsAmbiguousOrInconsistentOriginalSources() {
        for (String url : new String[]{
                "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=de",
                "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&lang=en",
                "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&%74lang=tr",
                "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&tlang",
                "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&kind=asr",
                "https://www.youtube.com/api/timedtext?v=short&lang=en"
        }) {
            assertNull(url, preview(new Track("en", url, ".en", "English"), "tr", Locale.ENGLISH));
        }
    }


    @Test public void fallbackValidatesOriginalUrlOnlyOnceForAllTargets() {
        class CountingTrack implements CaptionTranslationList.CaptionTrack {
            int urlReads, languageReads;
            public String universalCaptions_getLanguageCode() { languageReads++; return "ar"; }
            public String universalCaptions_getVssId() { return "a.ar"; }
            public String universalCaptions_getCaptionUrl() { urlReads++; return URL; }
            public CharSequence universalCaptions_getCaptionLabel() { return "Arabic"; }
            public CaptionTranslationList.CaptionTrack universalCaptions_copyTranslation(
                    String lang, String url, String vss, CharSequence label) {
                return new Track(lang, url, vss, label);
            }
        }
        CountingTrack source = new CountingTrack();
        var tracks = CaptionTranslationList.fromSource(OriginalCaptionSource.from(source), Locale.ENGLISH,
                java.util.List.of("ar", "tr", "de", "fr"));
        assertTrue(tracks.size() > 100);
        assertTrue(tracks.stream().anyMatch(track ->
                "tr".equals(track.universalCaptions_getLanguageCode())));
        // Validation snapshots the URL once and reuses it for every target.
        assertEquals(1, source.urlReads);
        assertEquals(1, source.languageReads);
    }
}

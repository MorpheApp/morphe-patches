/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;

public class EmptyTranslationFallbackTest {
    static final String URL = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=ar&kind=asr&sig=a%2Bb";
    static class Track implements CaptionTrack {
        final String code, url, vss;
        final CharSequence label;
        Track(String code, String url, String vss) { this(code, url, vss, code); }
        Track(String code, String url, String vss, CharSequence label) {
            this.code = code;
            this.url = url;
            this.vss = vss;
            this.label = label;
        }
        public String universalCaptions_getLanguageCode() { return code; }
        public String universalCaptions_getVssId() { return vss; }
        public String universalCaptions_getCaptionUrl() { return url; }
        public CharSequence universalCaptions_getCaptionLabel() { return label; }
        public CaptionTrack universalCaptions_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            return new Track(language, url, vss, label);
        }
    }
    static class Source implements EmptyTranslationFallback.CaptionSource {
        List<CaptionTrack> direct = List.of(new Track("DISABLE_CAPTIONS_OPTION", "", ""),
                new Track("ar", URL, "a.ar"));
        List<CaptionTrack> translations = List.of();
        List<String> targets; // null or empty: no target codes supplied by the native model
        CaptionTrack preferred, eligible;
        public List<String> universalCaptions_targetLanguages(boolean unused) { return targets; }
        public CaptionTrack universalCaptions_defaultSource(boolean unused) { return preferred; }
        public CaptionTrack universalCaptions_eligibleSource(boolean unused) { return eligible; }
        final CaptionTrack option = new Track("AUTO_TRANSLATE_CAPTIONS_OPTION", "", "");
        public List<CaptionTrack> universalCaptions_nativeDirect(boolean bypassFallback) { return direct; }
        public List<CaptionTrack> universalCaptions_nativeTranslated(boolean bypassFallback) { return translations; }
        public CaptionTrack universalCaptions_autoTranslateOption() { return option; }
    }
    @Test public void menuRowDoesNotAllocateTranslatedTracks() {
        Source source = new Source();
        source.direct = List.of(new Track("ar", URL, "a.ar") {
            @Override public CaptionTrack universalCaptions_copyTranslation(
                    String language, String url, String vss, CharSequence label) {
                throw new AssertionError("Menu availability must not synthesize tracks");
            }
        });
        assertEquals(2, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH).size());
    }

    @Test public void missingMenuGetsNativeOptionWithoutChangingOriginalOrOffEntry() {
        Source source = new Source();
        List<CaptionTrack> result = EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH);
        assertEquals(3, result.size());
        assertSame(source.direct.get(0), result.get(0));
        assertSame(source.direct.get(1), result.get(1));
        assertSame(source.option, result.get(2));
        assertEquals(2, source.direct.size());
    }
    @Test public void emptyTranslationMenuUsesSameSignedArabicSourceForAllTargets() {
        Source source = new Source();
        List<CaptionTrack> result = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
        assertTrue(result.size() > 100);
        CaptionTrack tr = result.stream().filter(t -> "tr".equals(t.universalCaptions_getLanguageCode())).findFirst().orElseThrow();
        assertEquals(URL + "&tlang=tr", tr.universalCaptions_getCaptionUrl());
        assertEquals("ta.ar.tr", tr.universalCaptions_getVssId());
        assertEquals("Turkish", tr.universalCaptions_getCaptionLabel());
        assertFalse(result.stream().anyMatch(t -> "ar".equals(t.universalCaptions_getLanguageCode())));
        assertEquals(0, source.translations.size());
        assertEquals(URL, source.direct.get(1).universalCaptions_getCaptionUrl());
    }
    @Test public void nonemptyNativeTranslationsAndExistingMenuRemainExactInputs() {
        Source source = new Source();
        source.translations = List.of(new Track("de", "invalid", "ta.ar.de"));
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
        assertEquals(source.translations, EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH));
        source.translations = List.of();
        source.direct = List.of(source.direct.get(0), source.direct.get(1), source.option);
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
        assertTrue(EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH).size() > 100);
    }
    @Test public void multipleSourcesAndMalformedTracksAreUntouched() {
        Source source = new Source();
        for (List<CaptionTrack> tracks : List.of(
                List.<CaptionTrack>of(new Track("ar", URL.replace("PlsODqqqwFs", "short"), "a.ar")),
                List.<CaptionTrack>of(new Track("ar", URL, ".ar")),
                List.<CaptionTrack>of(new Track("ar", URL + "&tlang=de", "a.ar")),
                List.<CaptionTrack>of(new Track("en", URL, "a.en")),
                List.<CaptionTrack>of(new Track("ar", URL + "&v=otherVideo1", "a.ar")),
                List.<CaptionTrack>of(new Track("ar", URL.replace("&lang=ar", "&lang=en"), "a.ar")),
                List.<CaptionTrack>of(new Track("ar", URL.replace("&sig=", "&%74lang=de&sig="), "a.ar")),
                List.<CaptionTrack>of(new Track("ar", URL.replace("&kind=asr", ""), "a.ar")),
                List.<CaptionTrack>of(new Track("ar", URL, "a.ar"), new Track("ar", URL.replace("PlsODqqqwFs", "abcdefghijk"), "a.ar")),
                Collections.<CaptionTrack>emptyList())) {
            source.direct = tracks;
            assertSame(tracks, EmptyTranslationFallback.direct(tracks, source, Locale.ENGLISH));
            assertEquals(source.translations, EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH));
        }
    }
    @Test public void sourcesRemainIndependentAcrossManagers() {
        Source first = new Source();
        Source second = new Source();
        second.direct = List.of(new Track("ar", URL.replace("PlsODqqqwFs", "short"), "a.ar"));
        assertEquals(3, EmptyTranslationFallback.direct(first.direct, first, Locale.ENGLISH).size());
        assertSame(second.direct, EmptyTranslationFallback.direct(second.direct, second, Locale.ENGLISH));
        first.direct = List.of(new Track("ar", URL, "a.ar"));
        assertTrue(EmptyTranslationFallback.translated(first.translations, first, Locale.ENGLISH).size() > 100);
    }
    @Test public void mixedNativeSourcesExpandOnlyFromCurrentAudioDefault() {
        Source source = new Source();
        String englishUrl = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=y";
        Track originalArabic = new Track("ar", URL, "a.ar");
        Track originalEnglish = new Track("en", englishUrl, ".en");
        source.direct = List.of(source.direct.get(0), originalArabic, originalEnglish);
        source.preferred = originalEnglish;
        Track nativeGerman = new Track("de", URL + "&tlang=de", "ta.ar.de", "German");
        Track nativeFrench = new Track("fr", englishUrl + "&tlang=fr", "t.en.fr", "French");
        source.translations = List.of(nativeGerman, nativeFrench);
        source.targets = List.of("de", "fr", "tr");

        List<CaptionTrack> translated = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue("Mixed native sources should not block a verified default", translated.size() > 100);
        assertTrue(translated.stream().anyMatch(t -> t == nativeGerman));
        assertTrue(translated.stream().anyMatch(t -> t == nativeFrench));
        assertEquals(englishUrl + "&tlang=tr", translated.stream()
                .filter(t -> "tr".equals(t.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());

        // Changing audio changes the native default: no source is cached.
        source.preferred = originalArabic;
        List<CaptionTrack> changedAudio = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertEquals(URL + "&tlang=tr", changedAudio.stream()
                .filter(t -> "tr".equals(t.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());
    }

    @Test public void mixedNativeSourcesUseAudioEligibleOriginalWithoutDefault() {
        Source source = new Source();
        String englishUrl = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=y";
        Track originalEnglish = new Track("en", englishUrl, ".en");
        Track originalArabic = new Track("ar", URL, "a.ar");
        source.direct = List.of(source.direct.get(0), originalArabic, originalEnglish);
        source.translations = List.of(
                new Track("de", URL + "&tlang=de", "ta.ar.de", "German"),
                new Track("fr", englishUrl + "&tlang=fr", "t.en.fr", "French"));
        source.targets = List.of("de", "fr", "tr");
        source.preferred = null; // As observed on Waydroid: hasDefault=false.
        source.eligible = originalEnglish;
        List<CaptionTrack> result = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue(result.size() > 100);
        assertEquals(englishUrl + "&tlang=tr", result.stream()
                .filter(t -> "tr".equals(t.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());

        // Audio has changed: no stale source is retained.
        source.eligible = originalArabic;
        result = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
        assertEquals(URL + "&tlang=tr", result.stream()
                .filter(t -> "tr".equals(t.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());
    }

    @Test public void audioEligibleOriginalTakesPrecedenceOverDifferentNativeDefault() {
        Source source = new Source();
        String englishUrl = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=y";
        Track originalEnglish = new Track("en", englishUrl, ".en");
        source.direct = List.of(source.direct.get(0), source.direct.get(1), originalEnglish);
        source.preferred = source.direct.get(1); // Native default does not imply target eligibility.
        source.eligible = originalEnglish;
        Track nativeGerman = new Track("de", URL + "&tlang=de", "ta.ar.de", "German");
        Track nativeFrench = new Track("fr", englishUrl + "&tlang=fr", "t.en.fr", "French");
        source.translations = List.of(nativeGerman, nativeFrench);
        source.targets = List.of("de", "fr", "tr");

        List<CaptionTrack> result = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue(result.size() > 100);
        assertEquals(englishUrl + "&tlang=tr", result.stream()
                .filter(t -> "tr".equals(t.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());
        assertTrue(result.containsAll(source.translations));
    }

    @Test public void repeatedMixedSourceMenusDoNotAccumulateOrMutateNativeTracks() {
        Source source = new Source();
        String englishUrl = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=y";
        Track originalEnglish = new Track("en", englishUrl, ".en");
        source.direct = List.of(source.direct.get(0), source.direct.get(1), originalEnglish);
        source.eligible = originalEnglish;
        source.targets = List.of("de", "fr", "tr");
        source.translations = List.of(
                new Track("de", URL + "&tlang=de", "ta.ar.de", "German"),
                new Track("fr", englishUrl + "&tlang=fr", "t.en.fr", "French"));
        List<CaptionTrack> nativeList = source.translations;

        List<CaptionTrack> first = EmptyTranslationFallback.translated(
                nativeList, source, Locale.ENGLISH);
        List<CaptionTrack> second = EmptyTranslationFallback.translated(
                nativeList, source, Locale.ENGLISH);
        assertTrue(first.size() > 100);
        assertEquals(first.size(), second.size());
        assertEquals(first.stream().map(CaptionTrack::universalCaptions_getCaptionUrl).toList(),
                second.stream().map(CaptionTrack::universalCaptions_getCaptionUrl).toList());
        assertSame(nativeList, source.translations);
        assertEquals(2, nativeList.size());
        assertSame(nativeList.get(0), first.stream()
                .filter(t -> "de".equals(t.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow());
    }

    @Test public void invalidNativeEligibleOriginalFailsClosed() {
        Source source = new Source();
        String englishUrl = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=y";
        source.direct = List.of(source.direct.get(0), source.direct.get(1),
                new Track("en", englishUrl, ".en"));
        source.translations = List.of(
                new Track("de", URL + "&tlang=de", "ta.ar.de", "German"),
                new Track("fr", englishUrl + "&tlang=fr", "t.en.fr", "French"));
        source.eligible = new Track("en", "https://evil.example/api/timedtext?v=PlsODqqqwFs&lang=en", ".en");
        List<CaptionTrack> result = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertEquals(source.translations.size(), result.size());
        assertTrue(result.containsAll(source.translations));
    }

    @Test public void mixedNativeSourcesWithoutNativeDefaultStayUntouched() {
        Source source = new Source();
        String englishUrl = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=y";
        source.direct = List.of(source.direct.get(0), source.direct.get(1),
                new Track("en", englishUrl, ".en"));
        source.translations = List.of(
                new Track("de", URL + "&tlang=de", "ta.ar.de", "German"),
                new Track("fr", englishUrl + "&tlang=fr", "t.en.fr", "French"));
        source.targets = List.of("de", "fr", "tr");
        List<CaptionTrack> unchanged = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertEquals(source.translations.size(), unchanged.size());
        assertTrue(unchanged.containsAll(source.translations));
        // A default from outside the audio-specific native original list is not trusted.
        source.preferred = new Track("en", englishUrl + "&name=unknown", ".en");
        List<CaptionTrack> afterWrongDefault = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertEquals(source.translations.size(), afterWrongDefault.size());
        assertTrue(afterWrongDefault.containsAll(source.translations));
    }

    @Test public void mixedNativeSourcesFromDifferentVideosDoNotMerge() {
        Source source = new Source();
        String foreign = "https://www.youtube.com/api/timedtext?v=otherVideo1&lang=en&sig=y";
        source.preferred = source.direct.get(1);
        source.translations = List.of(
                new Track("de", URL + "&tlang=de", "ta.ar.de", "German"),
                new Track("fr", foreign + "&tlang=fr", "t.en.fr", "French"));
        source.targets = List.of("de", "fr", "tr");
        List<CaptionTrack> unchanged = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertEquals(source.translations.size(), unchanged.size());
        assertTrue(unchanged.containsAll(source.translations));
    }

    @Test public void existingNativeTranslationsUseVideoMetadataWithoutLegacyMorpheAdapter() {
        Source source = new Source();
        source.targets = List.of("de", "tr");
        Track nativeGerman = new Track("de", URL + "&tlang=de", "ta.ar.de");
        source.translations = List.of(nativeGerman);

        List<CaptionTrack> result = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue(result.size() > 100);
        assertTrue(result.stream().anyMatch(track -> track == nativeGerman));
        assertEquals(URL + "&tlang=tr", result.stream()
                .filter(track -> "tr".equals(track.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());
    }

    @Test public void nullsAndFactoryFailuresFailClosed() {
        Source source = new Source();
        assertNull(EmptyTranslationFallback.direct(null, source, Locale.ENGLISH));
        assertNull(EmptyTranslationFallback.translated(null, source, Locale.ENGLISH));
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, null, Locale.ENGLISH));
        Source broken = new Source() {
            public CaptionTrack universalCaptions_autoTranslateOption() { throw new IllegalStateException("native failure"); }
        };
        assertSame(broken.direct, EmptyTranslationFallback.direct(broken.direct, broken, Locale.ENGLISH));
    }
    @Test public void namedEnglishSourceGetsNativeMenuAndTurkishTranslationWithoutAnAsrFlag() {
        Source source = new Source();
        String url = "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&name=896wrPZbXJE&sig=a%2Bb";
        CaptionTrack english = new Track("en", url, ".en.78oJUrdTLnk");
        source.direct = List.of(source.direct.get(0), english);
        List<CaptionTrack> direct = EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH);
        assertEquals(3, direct.size());
        assertSame(english, direct.get(1));
        assertSame(source.option, direct.get(2));
        List<CaptionTrack> translated = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
        CaptionTrack tr = translated.stream().filter(t -> "tr".equals(t.universalCaptions_getLanguageCode())).findFirst().orElseThrow();
        assertEquals(url + "&tlang=tr", tr.universalCaptions_getCaptionUrl());
        assertEquals("t.en.78oJUrdTLnk.tr", tr.universalCaptions_getVssId());
        assertFalse(translated.stream().anyMatch(t -> "en".equals(t.universalCaptions_getLanguageCode())));
    }

    @Test public void singleSourcesInOtherLanguagesAndVideosNeedNoAllowlist() {
        Source source = new Source();
        for (String language : List.of("de", "hi", "ja", "fr", "zh-Hans")) {
            String url = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=" + language + "&kind=asr&sig=a%2Bb";
            source.direct = List.of(new Track(language, url, "a." + language));
            assertEquals(2, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH).size());
            List<CaptionTrack> translations = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
            assertTrue(translations.size() > 100);
            assertFalse(translations.stream().anyMatch(t -> language.equals(t.universalCaptions_getLanguageCode())));
        }
    }

    @Test public void validSourcesInDifferentManagersNeverReuseEachOthersUrls() {
        Source arabic = new Source();
        Source english = new Source();
        String url = "https://www.youtube.com/api/timedtext?v=FlcePQVkmM4&lang=en&name=source";
        english.direct = List.of(new Track("en", url, ".en.source"));
        List<CaptionTrack> first = EmptyTranslationFallback.translated(arabic.translations, arabic, Locale.ENGLISH);
        List<CaptionTrack> second = EmptyTranslationFallback.translated(english.translations, english, Locale.ENGLISH);
        assertTrue(first.stream().allMatch(t -> t.universalCaptions_getCaptionUrl().contains("v=PlsODqqqwFs&")));
        assertTrue(second.size() > 100);
        assertTrue(second.stream().allMatch(t -> t.universalCaptions_getCaptionUrl().contains("v=FlcePQVkmM4&")));
        assertTrue(first.stream().noneMatch(t -> t.universalCaptions_getCaptionUrl().contains("FlcePQVkmM4")));
    }

    @Test public void nativeNonemptyCatalogIsNotAnArtificialSeventeenLanguageLimit() {
        Source source = new Source();
        source.targets = List.of("en", "de", "fr", "ru", "ar", "tr", "ja",
                "ko", "es", "pt", "it", "hi", "id", "nl", "th", "uk", "vi");
        List<CaptionTrack> result = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue("The enabled addon should offer additional targets", result.size() > 100);
        assertTrue(result.stream().anyMatch(track ->
                "el".equals(track.universalCaptions_getLanguageCode())));
        assertFalse(result.stream().anyMatch(track ->
                "ar".equals(track.universalCaptions_getLanguageCode())));
    }

    @Test public void nativeTargetCatalogIncludesNewAndRegionalCodesWithoutAnAllowlist() {
        Source source = new Source();
        source.targets = List.of("gaa", "pt", "pt-PT", "zh-Hans", "zh-Hant", "iw", "he", "bad&x=1");
        List<CaptionTrack> result = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
        var codes = result.stream().map(CaptionTrack::universalCaptions_getLanguageCode)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.containsAll(Set.of("gaa", "pt", "pt-PT", "zh-Hans", "zh-Hant", "iw", "tr")));
        assertFalse(codes.contains("he")); // Native 'iw' wins over its deprecated alias.
        assertFalse(codes.contains("bad&x=1"));
        assertTrue(result.size() > 100);
    }
    @Test public void multipleSourcesUseNativeDefaultAndRemainBoundToTheCurrentAudio() {
        Source source = new Source();
        CaptionTrack english = new Track("en", URL.replace("lang=ar&kind=asr", "lang=en"), ".en.upload");
        source.direct = List.of(source.direct.get(0), source.direct.get(1), english);
        source.preferred = english;
        source.targets = List.of("tr", "ja");
        List<CaptionTrack> result = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
        assertTrue(result.size() > 100);
        assertTrue(result.stream().allMatch(t -> t.universalCaptions_getCaptionUrl().contains("lang=en&")));
        assertEquals(4, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH).size());
        source.preferred = new Track("de", URL.replace("lang=ar", "lang=de"), "a.de");
        result = EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH);
        assertSame(source.translations, result);
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
    }

    @Test public void emptyProtoCatalogCanStillRestoreTheMissingMenu() {
        Source source = new Source();
        // Repeated protobuf fields do not distinguish missing from empty.
        source.targets = List.of();
        assertEquals(3, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH).size());
        assertTrue(EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH).size() > 100);
        source.targets = null;
        assertEquals(3, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH).size());
    }

    @Test public void sameVssWithDifferentSignedSourceUrlsMustNotGuess() {
        Source source = new Source();
        String base = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=x";
        Track first = new Track("en", base + "&name=first", ".en.same");
        Track second = new Track("en", base + "&name=second", ".en.same");
        source.direct = List.of(first, second);
        source.targets = List.of("tr");

        // VSS alone does not establish that two signed caption URLs are identical.
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
        assertSame(source.translations, EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH));

        // A matching native default resolves the ambiguity without guessing.
        source.preferred = second;
        var result = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue(result.size() > 100);
        assertEquals(second.url + "&tlang=tr", result.stream()
                .filter(track -> "tr".equals(track.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());
    }

    @Test public void twoSameLanguageSourcesRequireNativeDefaultToAvoidWrongTrack() {
        Source source = new Source();
        String root = "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=x";
        Track uploaded = new Track("en", root + "&name=uploaded", ".en.uploaded");
        Track alternate = new Track("en", root + "&name=alternate", ".en.alternate");
        source.direct = List.of(uploaded, alternate);
        source.targets = List.of("tr");

        // YouTube's actual source eligibility is by caption index, not language.
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
        assertSame(source.translations, EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH));

        // If YouTube supplies the native default, it is safe to use that source.
        source.preferred = alternate;
        List<CaptionTrack> translated = EmptyTranslationFallback.translated(
                source.translations, source, Locale.ENGLISH);
        assertTrue(translated.size() > 100);
        assertEquals(alternate.url + "&tlang=tr", translated.stream()
                .filter(track -> "tr".equals(track.universalCaptions_getLanguageCode()))
                .findFirst().orElseThrow().universalCaptions_getCaptionUrl());
    }

    @Test public void ambiguousMultipleSourceLanguagesDoNotGuessTheAudioTrack() {
        Source source = new Source();
        CaptionTrack english = new Track("en",
                "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=x", ".en");
        source.direct = List.of(source.direct.get(0), source.direct.get(1), english);
        source.targets = List.of("tr", "de");
        assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
        assertSame(source.translations, EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH));
    }

    @Test public void sourceOnlyOrInvalidCatalogCannotExposeAnEmptySubmenu() {
        Source source = new Source();
        for (List<String> targets : List.of(List.of("ar"), List.of("bad&query=1"))) {
            source.targets = targets;
            assertSame(source.direct, EmptyTranslationFallback.direct(source.direct, source, Locale.ENGLISH));
            assertSame(source.translations, EmptyTranslationFallback.translated(source.translations, source, Locale.ENGLISH));
        }
    }
    @Test public void everySnapshotSourceAndTargetKeepsItsExactLanguageAndSignedIdentity() {
        for (String language : CaptionTranslationList.TARGETS) {
            for (boolean asr : List.of(false, true)) {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("tr"), Locale.forLanguageTag("ar"), Locale.JAPANESE)) {
                Source source = new Source();
                String url = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=" + language + (asr ? "&kind=asr" : "") + "&sig=a%2Bb";
                source.direct = List.of(new Track(language, url, (asr ? "a." : ".") + language));
                source.targets = Arrays.asList(CaptionTranslationList.TARGETS);
                List<CaptionTrack> result = EmptyTranslationFallback.translated(source.translations, source, locale);
                assertEquals(language, 155, result.size());
                assertEquals(155, result.stream().map(CaptionTrack::universalCaptions_getLanguageCode).distinct().count());
                for (CaptionTrack target : result) {
                    assertEquals(url + "&tlang=" + target.universalCaptions_getLanguageCode(), target.universalCaptions_getCaptionUrl());
                    assertEquals((asr ? "ta." : "t.") + language + "." + target.universalCaptions_getLanguageCode(), target.universalCaptions_getVssId());
                    assertFalse(target.universalCaptions_getCaptionLabel().toString().isBlank());
                }
            }
            }
        }
    }

}

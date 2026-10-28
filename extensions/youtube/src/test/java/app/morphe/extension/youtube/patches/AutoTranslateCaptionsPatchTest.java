/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.text.Collator;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.youtube.patches.AutoTranslateCaptionsPatch.CaptionTrackInterface;

public class AutoTranslateCaptionsPatchTest {
    private static final String URL = "https://www.youtube.com/api/timedtext?v=video&lang=en&sig=a%2Bb";

    private static final class Track implements CaptionTrackInterface {
        final String code, url, vss, label;

        Track(String code, String label) {
            this(code, URL + "&tlang=" + code, "ta.en." + code, label);
        }

        Track(String code, String url, String vss, String label) {
            this.code = code;
            this.url = url;
            this.vss = vss;
            this.label = label;
        }

        @Override public String patch_getLanguageCode() { return code; }
        @Override public String patch_getVssId() { return vss; }
        @Override public String patch_getUrl() { return url; }
        @Override public CaptionTrackInterface patch_copyTranslation(
                String languageCode, String url, String vssId, CharSequence label) {
            return new Track(languageCode, url, vssId, label.toString());
        }
        @Override public String toString() { return label; }
    }

    private static List<CaptionTrackInterface> add(List<CaptionTrackInterface> tracks, Locale locale) {
        return AutoTranslateCaptionsPatch.addTranslationLanguages(tracks, locale);
    }

    private static CaptionTrackInterface find(List<CaptionTrackInterface> tracks, String code) {
        return tracks.stream().filter(t -> code.equals(t.patch_getLanguageCode()))
                .findFirst().orElseThrow(AssertionError::new);
    }

    @Test public void addsMissingLanguagesAndKeepsNativeTracks() {
        Track german = new Track("de", "German");
        List<CaptionTrackInterface> input = List.of(german);
        List<CaptionTrackInterface> result = add(input, Locale.ENGLISH);

        CaptionTrackInterface greek = find(result, "el");
        assertEquals(URL + "&tlang=el", greek.patch_getUrl());
        assertEquals("ta.en.el", greek.patch_getVssId());
        assertEquals("Greek", greek.toString());
        assertSame(german, find(result, "de"));
        assertEquals(List.of(german), input);
    }

    @Test public void doesNotDuplicateAliasesOrCollapseChineseScripts() {
        List<CaptionTrackInterface> result = add(List.of(
                new Track("de", "German"),
                new Track("iw", "Hebrew"),
                new Track("zh-Hans-TW", "Chinese (Simplified)")
        ), Locale.ENGLISH);

        assertFalse(result.stream().anyMatch(t -> "he".equals(t.patch_getLanguageCode())));
        assertFalse(result.stream().anyMatch(t -> "zh-Hans".equals(t.patch_getLanguageCode())));
        find(result, "zh-Hant");
    }

    @Test public void replacesOnlyTheTargetLanguageOfTheUrl() {
        Track german = new Track("de", URL + "&tlang=de&fmt=srv3#opaque", "ta.en.de", "German");
        List<CaptionTrackInterface> result = add(List.of(german), Locale.ENGLISH);

        assertEquals(URL + "&tlang=el&fmt=srv3#opaque", find(result, "el").patch_getUrl());
    }

    @Test public void mixedSourcesFromTheSameVideoStillExpandWithoutReplacingNativeTargets() {
        for (String video : List.of("bYsvNgA-tEE", "l8Sgq4zw1MY")) {
            String base = "https://www.youtube.com/api/timedtext?v=" + video;
            Track german = new Track("de", base + "&lang=en&kind=asr&sig=x%2By&tlang=de&fmt=srv3#opaque",
                    "ta.en.de", "German");
            String source = video.equals("bYsvNgA-tEE") ? "de" : "tr";
            Track english = new Track("en", base + "&lang=" + source + "&kind=asr&sig=other&tlang=en",
                    "ta." + source + ".en", "English");
            List<CaptionTrackInterface> result = add(List.of(german, english), Locale.ENGLISH);
            assertEquals(156, result.size());
            assertSame(german, find(result, "de"));
            assertSame(english, find(result, "en"));
            assertEquals(base + "&lang=en&kind=asr&sig=x%2By&tlang=tr&fmt=srv3#opaque",
                    find(result, "tr").patch_getUrl());
            assertEquals("ta.en.tr", find(result, "tr").patch_getVssId());
        }
    }

    @Test public void mixedSourceExpansionRequiresTheSameUnambiguousVideo() {
        Track german = new Track("de", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=de",
                "ta.en.de", "German");
        for (String url : List.of(
                "https://www.youtube.com/api/timedtext?v=zyxwvutsrqp&lang=tr&tlang=en",
                "https://www.youtube.com/api/timedtext?lang=tr&tlang=en",
                "https://www.youtube.com/api/timedtext?v=abcdefghijk&v=zyxwvutsrqp&lang=tr&tlang=en",
                "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=tr&tlang=en&tlang=fr")) {
            List<CaptionTrackInterface> nativeList = List.of(german, new Track("en", url, "ta.tr.en", "English"));
            assertSame(nativeList, add(nativeList, Locale.ENGLISH));
        }
    }

    @Test public void nativeTranslationTargetMustBeUnambiguousAndInTheActualQuery() {
        String base = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&sig=a%2Bb";
        for (String unsafe : List.of(
                base + "&tlang=de&tlang=de",
                base + "&%74lang=de&tlang=de",
                base + "&tlang=de-DE",
                base + "#opaque&tlang=de",
                base + "&tlang=de&tlang=fr",
                base.replace("youtube.com", "youtube.com.evil.test") + "&tlang=de"
        )) {
            List<CaptionTrackInterface> nativeList =
                    List.of(new Track("de", unsafe, "ta.en.de", "German"));
            assertSame(unsafe, nativeList, add(nativeList, Locale.ENGLISH));
        }
        Track valid = new Track("de", base + "&tlang=de&fmt=srv3#opaque", "ta.en.de", "German");
        assertEquals(base + "&tlang=tr&fmt=srv3#opaque",
                find(add(List.of(valid), Locale.ENGLISH), "tr").patch_getUrl());
    }

    @Test public void originalLanguageIsNotAddedAsItsOwnTranslation() {
        Track german = new Track("de", "German");
        List<CaptionTrackInterface> translated = add(List.of(german), Locale.ENGLISH);
        assertFalse(translated.stream().anyMatch(t -> "en".equals(t.patch_getLanguageCode())));
    }

    @Test public void keepsBrazilianAndEuropeanPortugueseSeparate() {
        List<CaptionTrackInterface> result = add(List.of(new Track("pt", "Portuguese")), Locale.ENGLISH);

        assertEquals(1, result.stream().filter(t -> "pt".equals(t.patch_getLanguageCode())).count());
        find(result, "pt-PT");
    }

    @Test public void sortsByDisplayedNameUsingLocaleCollation() {
        Track z = new Track("zu", "Zulu");
        Track a = new Track("af", "Älg");
        Track same = new Track("am", "älg");

        // Equivalent names keep their order.
        assertEquals(List.of(z, a, same), add(List.of(a, same, z), Locale.forLanguageTag("sv"))
                .stream().filter(t -> t == z || t == a || t == same).toList());
        assertEquals(List.of(a, same, z), add(List.of(z, a, same), Locale.GERMAN)
                .stream().filter(t -> t == z || t == a || t == same).toList());

        Collator collator = Collator.getInstance(Locale.ENGLISH);
        collator.setStrength(Collator.SECONDARY);
        List<CaptionTrackInterface> result = add(List.of(new Track("de", "German")), Locale.ENGLISH);
        for (int i = 1; i < result.size(); i++) {
            assertFalse(result.get(i - 1) + " > " + result.get(i),
                    collator.compare(result.get(i - 1).toString(), result.get(i).toString()) > 0);
        }
    }

    @Test public void emptyListIsUnchanged() {
        List<CaptionTrackInterface> empty = List.of();
        assertSame(empty, add(empty, Locale.ENGLISH));
    }
    @Test public void synthesizesNamedAndAsrSourcesWithoutLanguageOrVideoAllowlists() {
        for (String language : List.of("ar", "en", "el", "ja")) {
            for (boolean asr : List.of(false, true)) {
                String url = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=" + language
                        + (asr ? "&kind=asr" : "&name=uploaded%20track") + "&sig=a%2Bb#opaque";
                String vss = (asr ? "a." : ".") + language + ".named";
                Track source = new Track(language, url, vss, "Original");
                CaptionTrackInterface target = AutoTranslateCaptionsPatch.createTranslation(source, "bn", Locale.ENGLISH);
                assertEquals(url.replace("#opaque", "&tlang=bn#opaque"), target.patch_getUrl());
                assertEquals("t" + vss + ".bn", target.patch_getVssId());
                assertEquals(Locale.forLanguageTag("bn").getDisplayName(Locale.ENGLISH), target.toString());
            }
        }
    }

    @Test public void rejectsTranslatedAndUnsafeSources() {
        Track source = new Track("ar", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ar&kind=asr", "a.ar", "Arabic");
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.createTranslation(source, "ar", Locale.ENGLISH));
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.createTranslation(source, "bn&extra=1", Locale.ENGLISH));
        for (String url : List.of(source.url + "&tlang=de", source.url + "&lang=ar",
                source.url + "&%74lang=de", source.url.replace("youtube.com", "youtube.com.evil.test"),
                source.url.replace("lang=ar", "lang=en"))) {
            org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.createTranslation(
                    new Track("ar", url, "a.ar", "Arabic"), "bn", Locale.ENGLISH));
        }
        assertSame(source, AutoTranslateCaptionsPatch.findFallbackSource(List.of(source, source), null));
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(List.of(new Track("de", "German")), null));
        assertSame(source, AutoTranslateCaptionsPatch.findFallbackSource(List.of(
                new Track("DISABLE_CAPTIONS_OPTION", "", "-", "Off"), source,
                new Track("AUTO_TRANSLATE_CAPTIONS_OPTION", "", "", "Auto-translate")), null));
    }

    @Test public void sourceUrlMustBeAnUnambiguousOfficialTimedTextUrl() {
        String url = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&sig=a%2Bb&fmt=srv3";
        org.junit.Assert.assertNotNull(AutoTranslateCaptionsPatch.createTranslation(
                new Track("en", url.replace("www.youtube.com", "youtube.com"), ".en", "English"), "de", Locale.ENGLISH));

        for (String unsafe : List.of(url.replace("https:", "http:"), url.replace("/api/timedtext", "/watch"),
                url + "&v=other", url + "&%6cang=en", url + "&name=a&name=b", url + "&tlang")) {
            org.junit.Assert.assertNull(unsafe, AutoTranslateCaptionsPatch.createTranslation(
                    new Track("en", unsafe, ".en", "English"), "de", Locale.ENGLISH));
        }
    }

    @Test public void fallbackPreservesNativeListsAndAddsAllExistingCoreTargets() {
        Track source = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&name=named", ".en.uploaded", "English");
        List<CaptionTrackInterface> nativeList = List.of(new Track("de", "German"));
        assertSame(nativeList, AutoTranslateCaptionsPatch.restoreTranslationTracks(nativeList, List.of(source), null, Locale.ENGLISH));
        List<CaptionTrackInterface> result = AutoTranslateCaptionsPatch.restoreTranslationTracks(List.of(), List.of(source), null, Locale.ENGLISH);
        find(result, "bn"); find(result, "el");
        assertFalse(result.stream().anyMatch(t -> "en".equals(t.patch_getLanguageCode())));
        assertSame(result, AutoTranslateCaptionsPatch.restoreTranslationTracks(result, List.of(source), null, Locale.ENGLISH));
    }

    @Test public void preferredFallbackWorksWithoutAnAutoTranslateMenuAndReusesExistingTracks() {
        Track source = new Track("ar", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ar&kind=asr", "a.ar", "Arabic");
        var selected = PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source), List.of(), null, "bn", Locale.ENGLISH);
        assertEquals("bn", selected.patch_getLanguageCode());
        assertEquals("ta.ar.bn", selected.patch_getVssId());
        Track existing = new Track("bn", "Bengali");
        assertSame(existing, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source), List.of(existing), null, "bn", Locale.ENGLISH));
        assertEquals("ta.ar.bn", PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source, source), List.of(), null, "bn", Locale.ENGLISH).patch_getVssId());
    }

    @Test public void preferredFallbackNormalizesLocaleTargetsAndRejectsUnknownLanguages() {
        Track source = new Track("ar", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ar&kind=asr", "a.ar", "Arabic");
        assertEquals("en", PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source), List.of(), null, "en-us", Locale.ENGLISH).patch_getLanguageCode());
        assertEquals("iw", PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source), List.of(), null, "he", Locale.ENGLISH).patch_getLanguageCode());
        assertEquals("zh-Hant", PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source), List.of(), null, "zh-tw", Locale.ENGLISH).patch_getLanguageCode());
        org.junit.Assert.assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(source), List.of(), null, "qaa", Locale.ENGLISH));
    }

    @Test public void ambiguousOriginalsRequireAVerifiedCurrentAudioSource() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&name=English&sig=a%2Bb", ".en.named", "English");
        Track french = new Track("fr", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr&kind=asr", "a.fr", "French");
        List<CaptionTrackInterface> direct = List.of(english, french);
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(direct, null));
        assertSame(french, AutoTranslateCaptionsPatch.findFallbackSource(direct, french));
        assertSame(english, AutoTranslateCaptionsPatch.findFallbackSource(List.of(english, english), null));
        List<CaptionTrackInterface> empty = List.of();
        assertSame(empty, AutoTranslateCaptionsPatch.restoreTranslationTracks(empty, direct, null, Locale.ENGLISH));
        org.junit.Assert.assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.copyOf(direct), List.of(), null, "bn", Locale.ENGLISH));
        // Each audio's independently validated single-source list still works.
        assertSame(french, AutoTranslateCaptionsPatch.findFallbackSource(List.of(french), null));
    }

    @Test public void mixedVideoAndMalformedSourceListsCannotProduceFallbacks() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        for (Track other : List.of(
                new Track("fr", "https://www.youtube.com/api/timedtext?v=zyxwvutsrqp&lang=fr", ".fr", "French"),
                new Track("fr", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr&tlang=de", ".fr", "French"))) {
            List<CaptionTrackInterface> direct = List.of(english, other);
            org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(direct, null));
            List<CaptionTrackInterface> empty = List.of();
            assertSame(empty, AutoTranslateCaptionsPatch.restoreTranslationTracks(empty, direct, null, Locale.ENGLISH));
            org.junit.Assert.assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.copyOf(direct), List.copyOf(empty), null, "bn", Locale.ENGLISH));
        }
    }

    @Test public void menuAndPreferredFallbackUseTheSameNativeDefaultSource() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        Track french = new Track("fr", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr&name=French&sig=x%2By#opaque", ".fr.upload", "French");
        Track nativeDefault = new Track("fr", french.url, french.vss, "Native default");
        List<PreferredCaptionLanguagePatch.CaptionTrackInterface> direct = List.of(english, french);
        assertSame(french, AutoTranslateCaptionsPatch.findFallbackSource(direct, nativeDefault));
        var menu = AutoTranslateCaptionsPatch.restoreTranslationTracks(List.of(), direct, nativeDefault, Locale.ENGLISH);
        var selected = PreferredCaptionLanguagePatch.findAutoTranslatedTrack(direct, List.of(), nativeDefault, "bn", Locale.ENGLISH);
        assertEquals("t.fr.upload.bn", selected.patch_getVssId());
        assertEquals("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr&name=French&sig=x%2By&tlang=bn#opaque", find(menu, "bn").patch_getUrl());
        assertEquals(find(menu, "bn").patch_getUrl(), ((CaptionTrackInterface) selected).patch_getUrl());
        assertEquals(List.of(english, french), direct);
    }

    @Test public void defaultMustMatchBothSignedUrlAndVssInsideTheCurrentAudioList() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        Track french = new Track("fr", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr&sig=x%2By", ".fr.named", "French");
        List<CaptionTrackInterface> direct = List.of(english, french);
        for (Track absentDefault : List.of(
                new Track("fr", french.url + "&name=other", french.vss, "Different URL"),
                new Track("fr", french.url, ".fr.other", "Different VSS"),
                new Track("de", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=de", ".de", "Other audio"))) {
            org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(direct, absentDefault));
        }
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(List.of(french, english), null));
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(direct, null));
    }

    @Test public void defaultCannotBypassValidationOfTheRestOfTheSourceList() {
        Track source = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        for (Track other : List.of(
                new Track("fr", "https://www.youtube.com/api/timedtext?v=zyxwvutsrqp&lang=fr", ".fr", "Other video"),
                new Track("fr", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr&tlang=de", ".fr", "Translated"))) {
            org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(List.of(source, other), source));
        }
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(null, source));
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(List.of(), source));
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(java.util.Arrays.asList(source, null), source));
        org.junit.Assert.assertNull(AutoTranslateCaptionsPatch.findFallbackSource(List.of(
                new Track("DISABLE_CAPTIONS_OPTION", "", "-", "Off"),
                new Track("AUTO_TRANSLATE_CAPTIONS_OPTION", "", "", "Auto-translate")), source));
    }

    @Test public void nativeTranslationsStayPreferredOverMultipleSourceSynthesis() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        Track french = new Track("fr", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=fr", ".fr", "French");
        Track bengali = new Track("bn", "Bengali");
        List<CaptionTrackInterface> translations = List.of(bengali);
        assertSame(translations, AutoTranslateCaptionsPatch.restoreTranslationTracks(translations, List.of(english, french), french, Locale.ENGLISH));
        assertSame(bengali, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(english, french), List.of(bengali), french, "bn", Locale.ENGLISH));
    }

    @Test public void preferredFallbackPreservesPortugalPortuguese() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&sig=a%2Bb#opaque", ".en.named", "English");
        for (String preference : List.of("pt-PT", "pt-pt")) {
            var selected = PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(english), List.of(), null, preference, Locale.ENGLISH);
            assertEquals("pt-PT", selected.patch_getLanguageCode());
            assertEquals("t.en.named.pt-PT", selected.patch_getVssId());
            assertEquals("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&sig=a%2Bb&tlang=pt-PT#opaque", ((CaptionTrackInterface) selected).patch_getUrl());
        }
        assertEquals("pt", PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(english), List.of(), null, "pt-BR", Locale.ENGLISH).patch_getLanguageCode());
    }

    @Test public void preferredFallbackSupportsExistingInuktitutPreference() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        var selected = PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(english), List.of(), null, "iu", Locale.ENGLISH);
        org.junit.Assert.assertNotNull(selected);
        assertEquals("iu", selected.patch_getLanguageCode());
        assertEquals("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=iu", ((CaptionTrackInterface) selected).patch_getUrl());
    }

    @Test public void restoredMenuAndPreferredTrackKeepRegionalTargetsDistinct() {
        Track english = new Track("en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", ".en", "English");
        List<CaptionTrackInterface> menu = AutoTranslateCaptionsPatch.restoreTranslationTracks(List.of(), List.of(english), null, Locale.ENGLISH);
        CaptionTrackInterface portugal = find(menu, "pt-PT");
        find(menu, "pt"); find(menu, "iu");
        assertSame(portugal, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(english), List.copyOf(menu), null, "pt-PT", Locale.ENGLISH));
        assertEquals("pt", PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(english), List.copyOf(menu), null, "pt-BR", Locale.ENGLISH).patch_getLanguageCode());
    }

    @Test public void exactExistingTranslationWinsBeforeBroaderLanguageMatch() {
        Track portuguese = new Track("pt", "Portuguese");
        Track portugal = new Track("pt-PT", "Portuguese (Portugal)");
        assertSame(portugal, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(), List.of(portuguese, portugal), null, "pt-PT", Locale.ENGLISH));
        assertSame(portuguese, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(List.of(), List.of(portuguese), null, "pt-PT", Locale.ENGLISH));
    }

}

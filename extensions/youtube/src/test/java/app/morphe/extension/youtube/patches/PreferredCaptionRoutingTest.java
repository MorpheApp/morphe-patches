/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
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
    @Test public void chineseRegionRulesDoNotRejectOtherLanguages() {
        Track english = new Track("en-TW", "ta.ar.en-TW");
        Track german = new Track("de-CN", "ta.ar.de-CN");
        assertSame(english, find(List.of(english), "en-CN"));
        assertSame(german, find(List.of(german), "de-HK"));
    }

    @Test public void explicitChineseScriptTakesPriorityOverRegion() {
        Track simplified = new Track("zh-Hans-TW", "ta.en.zh-Hans-TW");
        Track traditional = new Track("zh-Hant-CN", "ta.en.zh-Hant-CN");
        assertSame(simplified, find(List.of(simplified), "zh-CN"));
        assertSame(traditional, find(List.of(traditional), "zh-TW"));
        assertNull(find(List.of(simplified), "zh-Hant"));
        assertNull(find(List.of(traditional), "zh-Hans"));
    }

    @Test public void legacyAliasesPreserveExactRegionalSelection() {
        Track generic = new Track("fil", "ta.en.fil");
        Track regional = new Track("tl-PH", "ta.en.tl-PH");
        Track norwegian = new Track("nb", "ta.en.nb");
        assertSame(regional, find(List.of(generic, regional), "fil-PH"));
        assertSame(norwegian, find(List.of(norwegian), "no"));
    }

    private static final class Source implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        final String language, url, vss;
        Source(String language, String url, String vss) { this.language=language; this.url=url; this.vss=vss; }
        public String patch_getLanguageCode() { return language; }
        public String patch_getUrl() { return url; }
        public String patch_getVssId() { return vss; }
        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(String language, String url, String vss, CharSequence label) {
            return new Source(language, url, vss);
        }
    }
    private static PreferredCaptionLanguagePatch.CaptionTrackInterface source(List<PreferredCaptionLanguagePatch.CaptionTrackInterface> tracks,
            PreferredCaptionLanguagePatch.CaptionTrackInterface currentAudio) {
        return PreferredCaptionLanguagePatch.blockTranslationSource(new PreferredCaptionLanguagePatch.SubtitleManagerInterface() {
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getDirectCaptionTracks() { return tracks; }
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAutoTranslateCaptionTracks() { return List.of(); }
            public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCaptionTranslationSource() { return null; }
            public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCurrentAudioCaptionSource() { return currentAudio; }
        }, null);
    }
    @Test public void sourceTranslationUsesTheValidatedCurrentAudioAsr() {
        Source english = new Source("en", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=en&kind=asr", "a.en");
        Source german = new Source("de", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=de&kind=asr", "a.de");
        assertSame(english, source(List.of(english), null));
        assertSame(german, source(List.of(english, german), german));
        assertNull(source(List.of(english, german), null));
    }
    @Test public void sourceTranslationAcceptsAuthoredButRejectsUnrelatedTracks() {
        Source authored = new Source("en", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=en", ".en");
        Source other = new Source("de", "https://www.youtube.com/api/timedtext?v=bYsvNgA-tEE&lang=de&kind=asr", "a.de");
        assertSame(authored, source(List.of(authored), authored));
        assertNull(source(List.of(authored, other), other));
        assertNull(source(List.of(), null));
    }
    @Test public void sourceTranslationPreservesAnAuthoredSelection() {
        Source asr = new Source("en", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=en&kind=asr", "a.en");
        Source authored = new Source("en", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=en", ".en");
        var manager = new PreferredCaptionLanguagePatch.SubtitleManagerInterface() {
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getDirectCaptionTracks() { return List.of(asr, authored); }
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAutoTranslateCaptionTracks() { return List.of(); }
            public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCaptionTranslationSource() { return asr; }
        };
        assertSame(authored, PreferredCaptionLanguagePatch.blockTranslationSource(manager, authored));
        assertSame(asr, PreferredCaptionLanguagePatch.blockTranslationSource(manager, asr));
    }
    @Test public void explicitSourceSelectionWinsOverConfiguredOutputLanguage() {
        assertEquals("de", PreferredCaptionLanguagePatch.blockTranslationTarget("de", "tr", true, Locale.ENGLISH));
        assertEquals("tr", PreferredCaptionLanguagePatch.blockTranslationTarget("en", "tr", false, Locale.ENGLISH));
        assertEquals("en", PreferredCaptionLanguagePatch.blockTranslationTarget("en", "off", false, Locale.ENGLISH));
        assertEquals("de", PreferredCaptionLanguagePatch.blockTranslationTarget("en", "default", false, Locale.GERMANY));
    }
    @Test public void nativeTranslationResolvesItsExactOriginalSource() {
        Source english = new Source("en", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=en&kind=asr", "a.en");
        Source german = new Source("de", "https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=de&kind=asr", "a.de");
        Source translated = new Source("fr", english.url + "&tlang=fr", "ta.en.fr");
        var manager = new PreferredCaptionLanguagePatch.SubtitleManagerInterface() {
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getDirectCaptionTracks() { return List.of(english, german); }
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAutoTranslateCaptionTracks() { return List.of(translated); }
            public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCaptionTranslationSource() { return german; }
        };
        assertSame(english, PreferredCaptionLanguagePatch.blockTranslationSource(manager, translated));
        Source missing = new Source("fr", english.url.replace("a.en", "a.es").replace("lang=en", "lang=es") + "&tlang=fr", "ta.es.fr");
        assertNull(PreferredCaptionLanguagePatch.blockTranslationSource(manager, missing));
        assertSame(german, PreferredCaptionLanguagePatch.blockTranslationSource(manager, german));
    }

    @Test public void selectedTurkishSourceWinsOverEnglishTranslationSource() {
        Source english = new Source("en", "https://www.youtube.com/api/timedtext?v=sleuwTieVeE&lang=en&kind=asr", "a.en");
        Source turkish = new Source("tr", "https://www.youtube.com/api/timedtext?v=sleuwTieVeE&lang=tr&kind=asr", "a.tr");
        var manager = new PreferredCaptionLanguagePatch.SubtitleManagerInterface() {
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getDirectCaptionTracks() { return List.of(english, turkish); }
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAutoTranslateCaptionTracks() { return List.of(); }
            public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCaptionTranslationSource() { return english; }
            public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCurrentAudioCaptionSource() { return turkish; }
        };
        assertSame(turkish, PreferredCaptionLanguagePatch.blockTranslationSource(manager, turkish));
        assertSame(turkish, PreferredCaptionLanguagePatch.blockTranslationSource(manager, null));
        Source stale = new Source("tr", turkish.url.replace("sleuwTieVeE", "RE3_PGwDwhw"), "a.tr");
        assertNull(PreferredCaptionLanguagePatch.blockTranslationSource(manager, stale));
    }

}

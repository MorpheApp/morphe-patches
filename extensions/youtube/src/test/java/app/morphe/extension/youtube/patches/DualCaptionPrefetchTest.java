/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;
import app.morphe.extension.youtube.patches.AutoTranslateCaptionsPatch.CaptionTrackInterface;
import app.morphe.extension.youtube.patches.PreferredCaptionLanguagePatch.SubtitleManagerInterface;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class DualCaptionPrefetchTest {
    private static final String URL = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&kind=asr&sig=a%2Bb%26c&&flag#keep%2F";
    private static final class Track implements CaptionTrackInterface {
        final String code, url, vss;
        Track(String code, String url, String vss) { this.code = code; this.url = url; this.vss = vss; }
        public String patch_getLanguageCode() { return code; }
        public String patch_getVssId() { return vss; }
        public String patch_getUrl() { return url; }
        public CaptionTrackInterface patch_copyTranslation(String code, String url, String vss, CharSequence label) {
            return new Track(code, url, vss);
        }
    }
    private static final Track SOURCE = new Track("en", URL, "a.en.audio_1");
    private static final class Manager implements SubtitleManagerInterface {
        final List<PreferredCaptionLanguagePatch.CaptionTrackInterface> direct;
        Manager(Track... tracks) { direct = List.of(tracks); }
        public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getDirectCaptionTracks() { return direct; }
        public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAutoTranslateCaptionTracks() { return List.of(); }
        public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCaptionTranslationSource() { return null; }
    }
    private static CaptionTrackInterface translated(String code) {
        return DualCaptionPrefetch.deriveTrack(SOURCE, code, null, Locale.ENGLISH);
    }

    @Test public void translatesOriginalWithOpaqueBytesAndVssSuffixIntact() {
        CaptionTrackInterface target = translated("de");
        assertNotNull(target);
        assertEquals(URL.replace("#", "&tlang=de#"), target.patch_getUrl());
        assertEquals("ta.en.audio_1.de", target.patch_getVssId());
        assertEquals(DualCaptionPrefetch.sourceIdentity(SOURCE), DualCaptionPrefetch.sourceIdentity(target));
    }
    @Test public void replacesTranslatedTargetWithoutReencodingSignedQuery() {
        CaptionTrackInterface german = translated("de");
        CaptionTrackInterface japanese = DualCaptionPrefetch.deriveTrack(german, "ja", null, Locale.ENGLISH);
        assertNotNull(japanese);
        assertEquals(german.patch_getUrl().replace("tlang=de", "tlang=ja"), japanese.patch_getUrl());
        assertEquals("ta.en.audio_1.ja", japanese.patch_getVssId());
        assertEquals(DualCaptionPrefetch.sourceIdentity(SOURCE), DualCaptionPrefetch.sourceIdentity(japanese));
    }
    @Test public void everyCatalogTargetCanBeDerivedFromTranslatedPrimary() {
        CaptionTrackInterface german = translated("de");
        for (String code : AutoTranslateCaptionsPatch.translationLanguages()) {
            if (AutoTranslateCaptionsPatch.getLanguageKey(code).equals("de")) continue;
            CaptionTrackInterface result = DualCaptionPrefetch.deriveTrack(german, code, new Manager(SOURCE), Locale.ENGLISH);
            assertNotNull(code, result);
            assertEquals(AutoTranslateCaptionsPatch.getLanguageKey(code),
                    AutoTranslateCaptionsPatch.getLanguageKey(result.patch_getLanguageCode()));
        }
    }
    @Test public void originalTargetUsesExactMatchingDirectNativeTrack() {
        CaptionTrackInterface german = translated("de");
        assertNull(DualCaptionPrefetch.deriveTrack(german, "en", null, Locale.ENGLISH));
        Track wrongVideo = new Track("en", URL.replace("abcdefghijk", "12345678901"), "a.en.audio_1");
        Track wrongAudio = new Track("en", URL, "a.en.audio_2");
        assertNull(DualCaptionPrefetch.deriveTrack(german, "en", new Manager(wrongVideo, wrongAudio), Locale.ENGLISH));
        assertSame(SOURCE, DualCaptionPrefetch.deriveTrack(german, "en", new Manager(wrongVideo, SOURCE), Locale.ENGLISH));
    }
    @Test public void rejectsInvalidTargetIdentityOrDuplicateParameters() {
        assertNull(translated("off"));
        assertNull(DualCaptionPrefetch.deriveTrack(new Track("de", URL.replace("#", "&tlang=de&tlang=de#"), "ta.en.audio_1.de"), "ja", null, Locale.ENGLISH));
        assertNull(DualCaptionPrefetch.deriveTrack(new Track("de", URL.replace("#", "&%74lang=de#"), "ta.en.audio_1.de"), "ja", null, Locale.ENGLISH));
        assertNull(DualCaptionPrefetch.deriveTrack(new Track("de", URL.replace("#", "&tlang=%64e#"), "ta.en.audio_1.de"), "ja", null, Locale.ENGLISH));
        assertNull(DualCaptionPrefetch.deriveTrack(new Track("de", URL.replace("#", "&tlang=de#"), "ta.fr.audio_1.de"), "ja", null, Locale.ENGLISH));
        assertNull(DualCaptionPrefetch.deriveTrack(new Track("de", URL.replace("#", "&tlang=fr#"), "ta.en.audio_1.de"), "ja", null, Locale.ENGLISH));
    }
    @Test public void rejectsNonNativeUrlAndMalformedDirectCandidates() {
        assertNull(DualCaptionPrefetch.sourceIdentity(new Track("en", URL.replace("www.youtube.com", "youtube.com.invalid"), "a.en")));
        assertNull(DualCaptionPrefetch.sourceIdentity(new Track("en", URL.replace("kind=asr", "kind=manual"), "a.en")));
        Track invalid = new Track("en", "https://example.com/", "a.en.audio_1");
        assertSame(SOURCE, DualCaptionPrefetch.deriveTrack(translated("de"), "en", new Manager(invalid, SOURCE), Locale.ENGLISH));
    }
    @Test public void targetInMiddleOfQueryAndFragmentIsReplacedExactlyOnce() {
        Track german = new Track("de", URL.replace("&sig=", "&tlang=de&sig=") + "tlang=de", "ta.en.audio_1.de");
        CaptionTrackInterface japanese = DualCaptionPrefetch.deriveTrack(german, "ja", null, Locale.ENGLISH);
        assertEquals(german.url.replace("&tlang=de&", "&tlang=ja&"), japanese.patch_getUrl());
    }
}

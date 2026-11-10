/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class UploadedCaptionTracksTest {
    @SuppressWarnings("unchecked")
    @Test
    public void prefetchedNativeTrackKeepsMetadataWithoutReplacingSelectedSession() throws Exception {
        var type = Class.forName(CaptionTracks.class.getName() + "$Track");
        var constructor = type.getDeclaredConstructor(
            String.class, String.class, String.class, String.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object track = constructor.newInstance("prefetched-source", "de", "tr", "26P1KyvrmjI", false, false);
        var parsingField = CaptionTracks.class.getDeclaredField("parsing");
        parsingField.setAccessible(true);
        var videoField = CaptionTracks.class.getDeclaredField("video");
        videoField.setAccessible(true);
        Object oldVideo = videoField.get(null);
        var sessionField = CaptionTracks.class.getDeclaredField("translatedSession");
        sessionField.setAccessible(true);
        Object oldSession = sessionField.get(null);
        ThreadLocal<Object> parsing = (ThreadLocal<Object>) parsingField.get(null);
        assertNotNull(parsing);
        try {
            videoField.set(null, "other-video");
            parsing.set(track);
            var state = new StableCaptionBlocks.State(false);
            CaptionTracks.observe(
                CaptionXml.read(
                    "<timedtext format='3'><body><p t='1000' d='1000'>Original</p></body></timedtext>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                state);
            assertNotNull("cached prefetch must remain capturable when selected later", state.captured);
            assertNotNull(state.policy);
            assertNull("prefetch cannot become the active output", state.translation);
            assertSame(oldSession, sessionField.get(null));
        } finally {
            videoField.set(null, oldVideo);
            parsing.remove();
        }
    }
    record Track(String language, String vss, String url)
        implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        public String patch_getLanguageCode() {
            return language;
        }
        public String patch_getVssId() {
            return vss;
        }
        public String patch_getUrl() {
            return url;
        }
        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
            String a, String b, String c, CharSequence d) {
            throw new AssertionError("Probe must not manufacture translation descriptors");
        }
    }
    private Track authored(String language) {
        return new Track(language, "." + language,
            "https://www.youtube.com/api/timedtext?v=26P1KyvrmjI&lang=" + language + "&caps=asr");
    }
    private static Object track(CaptionTracks.UploadedCandidate candidate) {
        assertNotNull(candidate);
        return candidate.track();
    }
    @Test
    public void findsSecondLanguageInFullCatalogWithoutSelectingAnotherTrack() {
        var tr = authored("tr");
        var de = authored("de");
        assertSame(de, track(CaptionTracks.findUploadedTrack(List.of(tr, de), "de")));
        assertSame(tr, track(CaptionTracks.findUploadedTrack(List.of(de, tr), "tr")));
    }
    @Test
    public void rejectsAsrAndTranslatedCandidates() {
        var asr =
            new Track("tr", "a.tr", "https://www.youtube.com/api/timedtext?v=26P1KyvrmjI&lang=tr&kind=asr");
        var translated = new Track(
            "tr", "t.en.tr", "https://www.youtube.com/api/timedtext?v=26P1KyvrmjI&lang=en&tlang=tr");
        assertNull(CaptionTracks.findUploadedTrack(List.of(asr, translated), "tr"));
    }
    @Test
    public void regionsAndScriptsDoNotCollapse() {
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("pt-BR")), "pt-PT"));
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("zh-Hans")), "zh-Hant"));
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("sr-Cyrl")), "sr-Latn"));
    }
    @Test
    public void canonicalAliasKeepsOriginalDescriptor() {
        var legacy = authored("iw");
        assertSame(legacy, track(CaptionTracks.findUploadedTrack(List.of(legacy), "he")));
    }
    @Test
    public void doesNotInventMissingLanguage() {
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("en")), "tr"));
    }
    @Test
    public void rejectsDuplicateIdentityParameters() {
        var bad = new Track("tr", ".tr", authored("tr").url() + "&lang=de");
        assertNull(CaptionTracks.findUploadedTrack(List.of(bad), "tr"));
    }
    @Test
    public void invalidNativeIdentifierIsRejectedWithoutCrashingCatalogScan() {
        var invalid = new Track("tr", null, authored("tr").url());
        assertNull(CaptionTracks.findUploadedTrack(List.of(invalid), "tr"));
    }

    @Test
    public void selectedVideoStateRequiresAnActualEnabledSelection() throws Exception {
        var type = CaptionTracks.class;
        var video = type.getDeclaredField("video");
        var source = type.getDeclaredField("source");
        var disabled = type.getDeclaredField("selectionDisabled");
        video.setAccessible(true);
        source.setAccessible(true);
        disabled.setAccessible(true);
        Object oldVideo = video.get(null), oldSource = source.get(null), oldDisabled = disabled.get(null);
        try {
            video.set(null, "26P1KyvrmjI");
            source.set(null, "source");
            disabled.set(null, false);
            assertTrue(CaptionTracks.isSelectedVideo("26P1KyvrmjI"));
            assertFalse(CaptionTracks.isSelectedVideo("differentId"));
            disabled.set(null, true);
            assertFalse(CaptionTracks.isSelectedVideo("26P1KyvrmjI"));
            disabled.set(null, false);
            source.set(null, null);
            assertFalse(CaptionTracks.isSelectedVideo("26P1KyvrmjI"));
            assertFalse(CaptionTracks.isSelectedVideo(null));
        } finally {
            video.set(null, oldVideo);
            source.set(null, oldSource);
            disabled.set(null, oldDisabled);
        }
    }
    @Test
    public void appDefaultCanUseGenericUploadedLanguage() {
        var english = authored("en");
        assertSame(english, track(CaptionTracks.findUploadedTrack(
            List.of(english), java.util.Locale.US.toLanguageTag(), true)));
        var turkish = authored("tr");
        assertSame(turkish, track(CaptionTracks.findUploadedTrack(List.of(turkish), "tr-TR", true)));
    }

    @Test
    public void appDefaultPrefersExactAndPreservesExplicitIdentity() {
        var generic = authored("en");
        var regional = authored("en-US");
        assertSame(regional, track(CaptionTracks.findUploadedTrack(
            List.of(generic, regional), "en-US", true)));
        assertNull(CaptionTracks.findUploadedTrack(List.of(generic), "en-US", false));
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("pt-BR")), "pt-PT", true));
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("pt")), "pt-PT", true));
    }

    @Test
    public void appDefaultNeverDropsAnExplicitScript() {
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("sr")), "sr-Cyrl", true));
        assertNull(CaptionTracks.findUploadedTrack(List.of(authored("zh-Hans")), "zh-Hant-TW", true));
        var traditional = authored("zh-Hant");
        assertSame(traditional, track(CaptionTracks.findUploadedTrack(
            List.of(traditional), "zh-Hant-TW", true)));
    }
}

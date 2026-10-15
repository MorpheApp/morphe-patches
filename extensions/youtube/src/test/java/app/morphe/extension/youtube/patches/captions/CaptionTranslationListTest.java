package app.morphe.extension.youtube.patches.captions;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class CaptionTranslationListTest {
    private static final String URL = "https://www.youtube.com/api/timedtext?v=video&lang=en&sig=a%2Bb&tlang=de&fmt=srv3";

    private static final class Track implements CaptionTranslationList.CaptionTrack {
        final String code, url, vss;
        final CharSequence label;

        Track(String code, String url, String vss, CharSequence label) {
            this.code = code;
            this.url = url;
            this.vss = vss;
            this.label = label;
        }

        @Override public String patch_getLanguageCode() { return code; }
        @Override public String patch_getVssId() { return vss; }
        @Override public String patch_getCaptionUrl() { return url; }
        @Override public CharSequence patch_getCaptionLabel() { return label; }
        @Override public CaptionTranslationList.CaptionTrack patch_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            return new Track(language, url, vss, label);
        }
    }

    private Track german() { return new Track("de", URL, "ta.en.de", "German"); }

    private CaptionTranslationList.CaptionTrack language(List<CaptionTranslationList.CaptionTrack> tracks, String code) {
        return tracks.stream().filter(t -> t != null && code.equals(t.patch_getLanguageCode()))
                .findFirst().orElseThrow(AssertionError::new);
    }

    @Test public void addsGreekUsingTheSameSignedSourceAndKeepsNativeObjects() {
        Track nativeGerman = german();
        List<CaptionTranslationList.CaptionTrack> input = List.of(nativeGerman);
        List<CaptionTranslationList.CaptionTrack> result = CaptionTranslationList.augment(input, Locale.ENGLISH);
        CaptionTranslationList.CaptionTrack greek = language(result, "el");
        assertEquals("https://www.youtube.com/api/timedtext?v=video&lang=en&sig=a%2Bb&tlang=el&fmt=srv3", greek.patch_getCaptionUrl());
        assertEquals("ta.en.el", greek.patch_getVssId());
        assertEquals("Greek", greek.patch_getCaptionLabel());
        assertSame(nativeGerman, language(result, "de"));
        assertEquals(List.of(nativeGerman), input);
    }

    @Test public void doesNotDuplicateAliasesOrCollapseChineseScripts() {
        List<CaptionTranslationList.CaptionTrack> result = CaptionTranslationList.augment(List.of(
                german(), new Track("iw", "", "", "Hebrew"),
                new Track("zh-Hans-TW", "", "", "Chinese (Simplified)")
        ), Locale.ENGLISH);
        assertFalse(result.stream().anyMatch(t -> "he".equals(t.patch_getLanguageCode())));
        assertFalse(result.stream().anyMatch(t -> "zh-Hans".equals(t.patch_getLanguageCode())));
        assertNotNull(language(result, "zh-Hant"));
        assertEquals(1, result.stream().filter(t -> "iw".equals(t.patch_getLanguageCode())).count());
    }

    @Test public void sortsDisplayedLabelsAndLeavesSpecialEntriesInPlace() {
        Track off = new Track("DISABLE_CAPTIONS_OPTION", "", "-", "Off");
        Track unknown = new Track("", "", "", "Unknown");
        Track zulu = new Track("zu", "", "", "Zulu");
        Track armenian = new Track("hy", "", "", "Armenian");
        Track german = new Track("de", "", "", "German");
        List<CaptionTranslationList.CaptionTrack> input = Arrays.asList(off, zulu, unknown, german, null, armenian);
        List<CaptionTranslationList.CaptionTrack> result = CaptionTranslationList.augment(input, Locale.ENGLISH);
        assertEquals(Arrays.asList(off, armenian, unknown, german, null, zulu), result);
        assertSame(zulu, input.get(1));
    }

    @Test public void usesLocaleCollationAndStableOrderForEquivalentLabels() {
        Track z = new Track("zu", "", "", "Zulu");
        Track a = new Track("af", "", "", "Älg");
        Track same = new Track("am", "", "", "älg");
        assertEquals(List.of(z, a, same), CaptionTranslationList.augment(List.of(a, same, z), Locale.forLanguageTag("sv")));
        assertEquals(List.of(a, same, z), CaptionTranslationList.augment(List.of(z, a, same), Locale.GERMAN));
    }

    @Test public void doesNotInventTranslationSupportForEmptyOrNonTranslatedLists() {
        assertNull(CaptionTranslationList.augment(null, Locale.ENGLISH));
        List<CaptionTranslationList.CaptionTrack> empty = List.of();
        assertSame(empty, CaptionTranslationList.augment(empty, Locale.ENGLISH));
        List<CaptionTranslationList.CaptionTrack> direct = List.of(new Track("en", URL.replace("&tlang=de", ""), ".en", "English"));
        assertEquals(direct, CaptionTranslationList.augment(direct, Locale.ENGLISH));
    }

    @Test public void rejectsUntrustedUrlsAndInconsistentTrackIdentities() {
        for (String url : List.of(URL.replace("https:", "http:"),
                URL.replace("www.youtube.com", "youtube.com.evil.test"),
                URL.replace("/api/timedtext", "/other"), URL + "&tlang=de",
                URL.replace("tlang=de", "tlang=fr"))) {
            Track track = new Track("de", url, "ta.en.de", "German");
            assertEquals(List.of(track), CaptionTranslationList.augment(List.of(track), Locale.ENGLISH));
        }
        Track wrongVss = new Track("de", URL, ".en", "German");
        assertEquals(List.of(wrongVss), CaptionTranslationList.augment(List.of(wrongVss), Locale.ENGLISH));
    }

    @Test public void expandingTwiceDoesNotAddDuplicatesOrChangeNativeIdentities() {
        List<CaptionTranslationList.CaptionTrack> once = CaptionTranslationList.augment(List.of(german()), Locale.ENGLISH);
        List<CaptionTranslationList.CaptionTrack> twice = CaptionTranslationList.augment(once, Locale.ENGLISH);
        assertEquals(once, twice);
        List<CaptionTranslationList.CaptionTrack> sorted = new ArrayList<>(once);
        sorted.sort((a, b) -> java.text.Collator.getInstance(Locale.ENGLISH).compare(a.patch_getCaptionLabel(), b.patch_getCaptionLabel()));
        assertEquals(sorted, once);
    }

    @Test public void onlyReplacesTheTargetQueryParameter() {
        Track track = new Track("de", URL + "&opaque=tlang=de#tlang=de", "ta.en.de", "German");
        CaptionTranslationList.CaptionTrack greek = language(CaptionTranslationList.augment(List.of(track), Locale.ENGLISH), "el");
        assertEquals("https://www.youtube.com/api/timedtext?v=video&lang=en&sig=a%2Bb&tlang=el&fmt=srv3&opaque=tlang=de#tlang=de", greek.patch_getCaptionUrl());
    }

    @Test public void aQuestionMarkInsideAnOpaqueValueIsNotATargetParameter() {
        Track track = new Track("de", URL.replace("&lang=en", "&opaque=?tlang=de&lang=en"), "ta.en.de", "German");
        CaptionTranslationList.CaptionTrack greek = language(CaptionTranslationList.augment(List.of(track), Locale.ENGLISH), "el");
        assertEquals("https://www.youtube.com/api/timedtext?v=video&opaque=?tlang=de&lang=en&sig=a%2Bb&tlang=el&fmt=srv3", greek.patch_getCaptionUrl());
    }
}

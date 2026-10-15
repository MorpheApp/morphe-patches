/**
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3754
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
}

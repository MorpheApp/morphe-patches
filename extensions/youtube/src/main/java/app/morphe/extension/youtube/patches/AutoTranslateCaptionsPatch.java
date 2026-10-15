/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AutoTranslateCaptionsPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface CaptionTrackInterface extends PreferredCaptionLanguagePatch.CaptionTrackInterface {
        // Methods are added during patching.

        String patch_getUrl();

        /**
         * @return A copy of this track with the given values.
         */
        CaptionTrackInterface patch_copyTranslation(String languageCode, String url,
                                                    String vssId, CharSequence label);
    }

    /**
     * Translation languages added if YouTube does not offer them.
     * Not all languages may be available for all videos.
     */
    private static final String[] TRANSLATION_LANGUAGES = {
            "af", "am", "ar", "as", "az", "be", "bg", "bho", "bn", "bs", "ca", "ceb", "co", "cs",
            "cy", "da", "de", "dv", "el", "en", "eo", "es", "et", "eu", "fa", "fi", "fil", "fr",
            "fy", "ga", "gd", "gl", "gu", "ha", "haw", "he", "hi", "hmn", "hr", "ht", "hu", "hy",
            "id", "ig", "is", "it", "ja", "jv", "ka", "kk", "km", "kn", "ko", "ku", "ky", "la",
            "lb", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt", "my", "ne",
            "nl", "no", "ny", "or", "pa", "pl", "ps", "pt", "ro", "ru", "rw", "sd", "si", "sk",
            "sl", "sm", "sn", "so", "sq", "sr", "st", "su", "sv", "sw", "ta", "te", "tg", "th",
            "tk", "tr", "tt", "ug", "uk", "ur", "uz", "vi", "xh", "yi", "yo", "zh-Hans", "zh-Hant",
            "zu"
    };

    /**
     * Injection point: Called after the auto-translated caption tracks are created.
     */
    public static List<CaptionTrackInterface> addTranslationLanguages(List<CaptionTrackInterface> tracks) {
        if (!Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get()) {
            return tracks;
        }

        try {
            return addTranslationLanguages(tracks, Requester.getAppLocale());
        } catch (Exception ex) {
            Logger.printException(() -> "addTranslationLanguages failure", ex);
            return tracks;
        }
    }

    static List<CaptionTrackInterface> addTranslationLanguages(List<CaptionTrackInterface> tracks,
                                                               Locale locale) {
        if (tracks.isEmpty()) {
            // Video has no auto-translation.
            return tracks;
        }

        // All tracks are translations of the same source track.
        // Vss id is "t" + source vss id + "." + language code,
        // and the url ends with "&tlang=" + language code.
        CaptionTrackInterface template = tracks.get(0);
        final int templateCodeLength = template.patch_getLanguageCode().length();
        String vssIdPrefix = removeSuffix(template.patch_getVssId(), templateCodeLength);
        String urlPrefix = removeSuffix(template.patch_getUrl(), templateCodeLength);

        Set<String> existingLanguages = new HashSet<>();
        for (CaptionTrackInterface track : tracks) {
            existingLanguages.add(getLanguageKey(track.patch_getLanguageCode()));
        }

        List<CaptionTrackInterface> result = new ArrayList<>(tracks);
        for (String code : TRANSLATION_LANGUAGES) {
            if (existingLanguages.add(getLanguageKey(code))) {
                String name = Locale.forLanguageTag(code).getDisplayName(locale);
                result.add(template.patch_copyTranslation(
                        code, urlPrefix + code, vssIdPrefix + code, name));
            }
        }

        Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.SECONDARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        // Track toString() is the displayed name.
        result.sort((a, b) -> collator.compare(a.toString(), b.toString()));

        return result;
    }

    private static String removeSuffix(String value, int suffixLength) {
        return value.substring(0, value.length() - suffixLength);
    }

    /**
     * @return Key that is the same for all codes of a language.
     */
    private static String getLanguageKey(String languageCode) {
        String code = languageCode.replace('_', '-').toLowerCase(Locale.ROOT);
        String primary = code.split("-")[0];
        switch (primary) {
            // Legacy ISO 639 codes used by Java Locale and YouTube.
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            case "tl": return "fil";
            case "nb": return "no";
            // Traditional and Simplified Chinese are different translations.
            case "zh":
                // Script takes priority over region.
                if (code.contains("-hant")) return "zh-hant";
                if (code.contains("-hans")) return "zh-hans";
                if (code.endsWith("-tw") || code.endsWith("-hk")) return "zh-hant";
                if (code.endsWith("-cn") || code.endsWith("-sg")) return "zh-hans";
                return code;
            default:
                return primary;
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern LANGUAGE_CODE_PATTERN = Pattern.compile("[a-z]{2,8}(-[A-Za-z0-9]{2,8})*");
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VSS_ID_SUFFIX_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    /**
     * Translation languages added if YouTube does not offer them.
     * Not all languages may be available for all videos.
     */
    private static final String[] TRANSLATION_LANGUAGES = {
            "af", "am", "ar", "as", "az", "be", "bg", "bho", "bn", "bs", "ca", "ceb", "co", "cs",
            "cy", "da", "de", "dv", "el", "en", "eo", "es", "et", "eu", "fa", "fi", "fil", "fr",
            "fy", "ga", "gd", "gl", "gu", "ha", "haw", "he", "hi", "hmn", "hr", "ht", "hu", "hy",
            "id", "ig", "is", "it", "iu", "ja", "jv", "ka", "kk", "km", "kn", "ko", "ku", "ky", "la",
            "lb", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt", "my", "ne",
            "nl", "no", "ny", "or", "pa", "pl", "ps", "pt", "pt-PT", "ro", "ru", "rw", "sd", "si", "sk",
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

        return sortTranslationTracks(result, locale);
    }

    private static List<CaptionTrackInterface> sortTranslationTracks(List<CaptionTrackInterface> result, Locale locale) {
        Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.SECONDARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        // Track toString() is the displayed name.
        result.sort((a, b) -> collator.compare(a.toString(), b.toString()));

        return result;
    }

    /** Keep YouTube's native menu construction and its other availability checks. */
    public static int getTranslationCount(int nativeCount,
            List<? extends PreferredCaptionLanguagePatch.CaptionTrackInterface> directTracks) {
        try {
            return nativeCount > 0 || !Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get()
                    || findFallbackSource(directTracks, null) == null ? nativeCount : 1;
        } catch (Exception ex) {
            Logger.printException(() -> "getTranslationCount failure", ex);
            return nativeCount;
        }
    }

    /** Called by the manager adapter, not recursively from either native list getter. */
    public static List<CaptionTrackInterface> restoreTranslationTracks(List<CaptionTrackInterface> tracks,
            List<? extends PreferredCaptionLanguagePatch.CaptionTrackInterface> directTracks,
            PreferredCaptionLanguagePatch.CaptionTrackInterface defaultSource) {
        if (!Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get()) return tracks;
        try {
            return restoreTranslationTracks(tracks, directTracks, defaultSource, Requester.getAppLocale());
        } catch (Exception ex) {
            Logger.printException(() -> "restoreTranslationTracks failure", ex);
            return tracks;
        }
    }

    static List<CaptionTrackInterface> restoreTranslationTracks(List<CaptionTrackInterface> tracks,
            List<? extends PreferredCaptionLanguagePatch.CaptionTrackInterface> directTracks,
            PreferredCaptionLanguagePatch.CaptionTrackInterface defaultSource, Locale locale) {
        if (tracks == null || !tracks.isEmpty()) return tracks;
        CaptionTrackInterface source = findFallbackSource(directTracks, defaultSource);
        if (source == null) return tracks;
        List<CaptionTrackInterface> result = new ArrayList<>();
        for (String language : TRANSLATION_LANGUAGES) {
            CaptionTrackInterface translated = createTranslation(source, language, locale);
            if (translated != null) result.add(translated);
        }
        return result.isEmpty() ? tracks : sortTranslationTracks(result, locale);
    }

    /** Prefer the native default within this audio's originals, otherwise retain their order. */
    static CaptionTrackInterface findFallbackSource(
            List<? extends PreferredCaptionLanguagePatch.CaptionTrackInterface> tracks,
            PreferredCaptionLanguagePatch.CaptionTrackInterface defaultSource) {
        if (tracks == null) return null;
        CaptionTrackInterface first = null, preferred = null;
        String video = null;
        for (PreferredCaptionLanguagePatch.CaptionTrackInterface track : tracks) {
            if (track == null) return null;
            String code = track.patch_getLanguageCode();
            if (PreferredCaptionLanguagePatch.DISABLE_OPTION.equals(code)
                    || PreferredCaptionLanguagePatch.AUTO_TRANSLATE_OPTION.equals(code)) continue;
            if (!(track instanceof CaptionTrackInterface candidate)) return null;
            String identity = originalVideoId(candidate);
            if (identity == null || video != null && !video.equals(identity)) return null;
            video = identity;
            if (first == null) first = candidate;
            if (defaultSource instanceof CaptionTrackInterface nativeDefault
                    && candidate.patch_getVssId().equals(nativeDefault.patch_getVssId())
                    && candidate.patch_getUrl().equals(nativeDefault.patch_getUrl())) preferred = candidate;
        }
        return preferred != null ? preferred : first;
    }

    static String translationLanguage(String language) {
        if (language == null || !LANGUAGE_CODE_PATTERN.matcher(language).matches()) return null;
        for (String code : TRANSLATION_LANGUAGES) {
            if (code.equalsIgnoreCase(language)) return code;
        }
        for (String code : TRANSLATION_LANGUAGES) {
            if (getLanguageKey(code).equals(getLanguageKey(language))) return code;
        }
        return null;
    }

    static CaptionTrackInterface createTranslation(CaptionTrackInterface source, String language, Locale locale) {
        if (source == null || language == null || !LANGUAGE_CODE_PATTERN.matcher(language).matches()
                || originalVideoId(source) == null
                || getLanguageKey(language).equals(getLanguageKey(source.patch_getLanguageCode()))) return null;
        String url = source.patch_getUrl();
        int fragment = url.indexOf('#');
        if (fragment < 0) fragment = url.length();
        String name = Locale.forLanguageTag(language).getDisplayName(locale);
        return source.patch_copyTranslation(language,
                url.substring(0, fragment) + "&tlang=" + language + url.substring(fragment),
                "t" + source.patch_getVssId() + "." + language, name.isEmpty() ? language : name);
    }

    /** Validate identity without decoding/re-encoding signed URL bytes. */
    private static String originalVideoId(CaptionTrackInterface source) {
        String language = source.patch_getLanguageCode();
        if (language == null || !LANGUAGE_CODE_PATTERN.matcher(language).matches()) return null;
        String vss = source.patch_getVssId();
        boolean asr = originalVss(vss, "a." + language);
        if (!asr && !originalVss(vss, "." + language)) return null;
        try {
            URI uri = URI.create(source.patch_getUrl());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !("youtube.com".equalsIgnoreCase(host) || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com"))
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !"/api/timedtext".equals(uri.getRawPath()) || uri.getRawQuery() == null) return null;
            Map<String, String> parameters = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int split = part.indexOf('=');
                String rawKey = split < 0 ? part : part.substring(0, split);
                String key = URLDecoder.decode(rawKey, "UTF-8");
                if (!List.of("v", "lang", "kind", "name", "tlang").contains(key)) continue;
                if (!key.equals(rawKey) || parameters.containsKey(key) || key.equals("tlang")) return null;
                parameters.put(key, URLDecoder.decode(split < 0 ? "" : part.substring(split + 1), "UTF-8"));
            }
            String video = parameters.get("v");
            String sourceLanguage = parameters.get("lang");
            String kind = parameters.get("kind");
            if (video == null || !VIDEO_ID_PATTERN.matcher(video).matches() || sourceLanguage == null
                    || !LANGUAGE_CODE_PATTERN.matcher(sourceLanguage).matches()
                    || !getLanguageKey(language).equals(getLanguageKey(sourceLanguage))
                    || (asr ? !"asr".equals(kind) : kind != null && !kind.isEmpty())) return null;
            return video;
        } catch (IllegalArgumentException | UnsupportedEncodingException ex) {
            Logger.printDebug(() -> "originalVideoId rejected track url", ex);
            return null;
        }
    }

    private static boolean originalVss(String value, String prefix) {
        return value != null && (value.equals(prefix) || value.startsWith(prefix + ".")
                && VSS_ID_SUFFIX_PATTERN.matcher(value.substring(prefix.length() + 1)).matches());
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

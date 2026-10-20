/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Extends a native translation list without selecting a caption track or changing caption visibility. */
public final class CaptionTranslationList {
    private CaptionTranslationList() { }

    /** Implemented by the native track class at patch time. */
    public interface CaptionTrack {
        String universalCaptions_getLanguageCode();
        String universalCaptions_getVssId();
        String universalCaptions_getCaptionUrl();
        CharSequence universalCaptions_getCaptionLabel();
        CaptionTrack universalCaptions_copyTranslation(String language, String url, String vss, CharSequence label);
    }

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,8}(-[A-Za-z0-9]{2,8})*");

    // YouTube player translationLanguages snapshot, 2026-10-16, video FlcePQVkmM4.
    // Preserve native target entries; use snapshot codes only for compatible missing choices.
    static final String[] TARGETS = "ab aa af ak sq am ar hy as ay az bn ba eu be bho bs br bg my ca ceb zh-Hans zh-Hant co hr cs da dv nl dz en eo et ee fo fj fil fi fr gaa gl lg ka de el gn gu ht ha haw iw hi hmn hu is ig id iu ga it ja jv kl kn kk kha km rw ko kri ku ky lo la lv ln lt lua luo lb mk mg ms ml mt gv mi mr mn mfe ne new nso no ny oc or om os pam ps fa pl pt pt-PT pa qu ro rn ru sm sg sa gd sr crs sn sd si sk sl so st es su sw ss sv tg ta tt te th bo ti to ts tn tum tr tk uk ur ug uz ve vi war cy fy wo xh yi yo zu".split(" ");

    static boolean validLanguageCode(String code) {
        return code != null && LANGUAGE_CODE.matcher(code).matches();
    }

    static List<String> targets(List<String> nativeTargets) {
        if (nativeTargets == null || nativeTargets.isEmpty()) return java.util.Arrays.asList(TARGETS);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : nativeTargets) {
            if (validLanguageCode(code) && seen.add(languageKey(code))) result.add(code);
        }
        // Keep the native catalog as the first source of valid target codes.
        return result;
    }

    /** Widen a proven source-compatible native catalog without replacing its native tracks.
     * Extra snapshot targets are best-effort: the server may reject an unsupported tlang.
     */
    static List<String> expandedTargets(List<String> nativeTargets,
            java.util.function.Predicate<String> sourceAllows) {
        List<String> nativeCodes = targets(nativeTargets);
        if (nativeTargets == null || nativeTargets.isEmpty() ||
                nativeCodes.stream().noneMatch(sourceAllows)) return nativeCodes;

        List<String> expanded = new ArrayList<>(nativeCodes);
        Set<String> seen = new HashSet<>();
        for (String code : nativeCodes) seen.add(languageKey(code));
        for (String code : TARGETS) {
            if (seen.add(languageKey(code))) expanded.add(code);
        }
        return expanded;
    }

    /** Select an alternate native audio-compatible original only when target cloning cannot extend the list. */
    static List<CaptionTrack> augment(List<CaptionTrack> tracks, Locale locale, List<String> nativeTargets,
            Supplier<OriginalCaptionSource> nativeOriginal) {
        if (tracks == null || tracks.isEmpty() || locale == null) return tracks;
        List<CaptionTrack> result = new ArrayList<>(tracks);
        Set<String> represented = new HashSet<>();
        CaptionTrack prototype = null;
        String sourceIdentity = null;
        for (CaptionTrack track : tracks) {
            if (track == null) continue;
            represented.add(languageKey(track.universalCaptions_getLanguageCode()));
            String identity = translationSourceIdentity(track);
            if (identity == null) continue;
            if (prototype != null && !sourceIdentity.equals(identity)) {
                // Preserve YouTube's target-specific native source objects. A native
                // audio-compatible original may supply only the missing targets.
                return unresolved(tracks, result, locale, nativeTargets, nativeOriginal);
            }
            if (prototype == null) {
                prototype = track;
                sourceIdentity = identity;
            }
        }
        if (prototype != null) {
            String oldLanguage = prototype.universalCaptions_getLanguageCode();
            String oldUrl = prototype.universalCaptions_getCaptionUrl();
            String oldVss = prototype.universalCaptions_getVssId();
            String vssPrefix = oldVss.substring(0, oldVss.length() - oldLanguage.length());

            // Keep opaque/signed bytes untouched, replacing only the real query target.
            TimedTextUrl timedText = TimedTextUrl.parse(oldUrl);
            int targetAt = timedText == null ? -1 : timedText.targetValueOffset(oldLanguage);
            if (targetAt < 0)
                return unresolved(tracks, result, locale, nativeTargets, nativeOriginal);
            String urlPrefix = oldUrl.substring(0, targetAt);
            String urlSuffix = oldUrl.substring(targetAt + oldLanguage.length());
            String sourceKey = languageKey(timedText.field("lang"));

            for (String code : expandedTargets(nativeTargets,
                    code -> !sourceKey.equals(languageKey(code)))) {
                String key = languageKey(code);
                if (sourceKey.equals(key) || !represented.add(key)) continue;
                String name = Locale.forLanguageTag(code).getDisplayName(locale);
                if (name.isEmpty()) name = code;
                result.add(prototype.universalCaptions_copyTranslation(
                        code, urlPrefix + code + urlSuffix, vssPrefix + code, name));
            }
        }
        return result.size() > tracks.size() ? sorted(result, locale)
                : unresolved(tracks, result, locale, nativeTargets, nativeOriginal);
    }

    private static List<CaptionTrack> unresolved(List<CaptionTrack> original,
            List<CaptionTrack> unchanged, Locale locale, List<String> nativeTargets,
            Supplier<OriginalCaptionSource> nativeOriginal) {
        if (nativeOriginal == null) return sorted(unchanged, locale);
        return mergeFromSelectedSource(original, nativeOriginal.get(), locale, nativeTargets);
    }

    private static void appendFromSource(List<CaptionTrack> output, Set<String> represented,
            OriginalCaptionSource source, Locale locale, List<String> nativeTargets) {
        for (String target : expandedTargets(nativeTargets, source::canTranslate)) {
            if (represented.contains(languageKey(target))) continue;
            CaptionTrack translated = source.translate(target, locale);
            if (translated != null) {
                output.add(translated);
                represented.add(languageKey(target));
            }
        }
    }

    static List<CaptionTrack> fromSource(OriginalCaptionSource source, Locale locale, List<String> nativeTargets) {
        List<CaptionTrack> result = new ArrayList<>();
        if (locale == null || source == null) return result;
        appendFromSource(result, new HashSet<>(), source, locale, nativeTargets);
        return sorted(result, locale);
    }

    /** Keep native per-target routing; fill missing targets from the verified
     * current-audio eligible original, never from an arbitrary translated track.
     */
    static List<CaptionTrack> mergeFromSelectedSource(List<CaptionTrack> nativeTracks,
            OriginalCaptionSource selected, Locale locale, List<String> nativeTargets) {
        if (nativeTracks == null || nativeTracks.isEmpty() || selected == null || locale == null)
            return nativeTracks;
        Set<String> represented = new HashSet<>();
        for (CaptionTrack track : nativeTracks) {
            if (track == null || translationSourceIdentity(track) == null)
                return nativeTracks;
            TimedTextUrl url = TimedTextUrl.parse(track.universalCaptions_getCaptionUrl());
            if (url == null || url.field("v") != null
                    && !selected.videoId().equals(url.field("v")))
                return nativeTracks;
            represented.add(languageKey(track.universalCaptions_getLanguageCode()));
        }

        List<CaptionTrack> result = new ArrayList<>(nativeTracks);
        appendFromSource(result, represented, selected, locale, nativeTargets);
        return sorted(result, locale);
    }

    /** Validate each native translated track and identify its original source and video. */
    private static String translationSourceIdentity(CaptionTrack track) {
        String code = track.universalCaptions_getLanguageCode();
        String vss = track.universalCaptions_getVssId();
        if (!validLanguageCode(code) || vss == null ||
                !vss.startsWith("t") || !vss.endsWith("." + code)) return null;

        TimedTextUrl url = TimedTextUrl.parse(track.universalCaptions_getCaptionUrl());
        if (url == null || !code.equals(url.field("tlang"))) return null;
        String sourceUrl = url.sourceIdentity(code);
        if (sourceUrl == null) return null;
        String sourceLanguage = url.field("lang");
        if (!validLanguageCode(sourceLanguage)) return null;
        String video = url.field("v");
        if (video != null && !video.matches("[A-Za-z0-9_-]{1,128}")) return null;

        String originalVss = vss.substring(1, vss.length() - code.length() - 1);
        if (!OriginalCaptionSource.originalVss(originalVss, "a." + sourceLanguage)
                && !OriginalCaptionSource.originalVss(originalVss, "." + sourceLanguage))
            return null;
        return vss.substring(0, vss.length() - code.length()) + "|" + sourceUrl;
    }

    static String languageKey(String raw) {
        if (raw == null) return "";
        String code = raw.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int dash = code.indexOf('-');
        String primary = dash < 0 ? code : code.substring(0, dash);
        String region = dash < 0 ? "" : code.substring(dash);
        switch (primary) {
            case "iw": return "he" + region;
            case "in": return "id" + region;
            case "ji": return "yi" + region;
            case "tl": return "fil" + region;
            case "nb": return "no" + region;
            case "zh":
                if (code.contains("-hant")) return "zh-hant";
                if (code.contains("-hans")) return "zh-hans";
                if (code.endsWith("-tw") || code.endsWith("-hk")) return "zh-hant";
                if (code.endsWith("-cn") || code.endsWith("-sg")) return "zh-hans";
                return code;
            default: return code;
        }
    }

    private static List<CaptionTrack> sorted(List<CaptionTrack> tracks, Locale locale) {
        List<Integer> positions = new ArrayList<>();
        List<CaptionTrack> languages = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            CaptionTrack track = tracks.get(i);
            if (track == null) continue;
            String code = track.universalCaptions_getLanguageCode();
            CharSequence label = track.universalCaptions_getCaptionLabel();
            if (code == null || code.isEmpty() || code.endsWith("_OPTION") ||
                    label == null || label.toString().trim().isEmpty()) continue;
            positions.add(i);
            languages.add(track);
        }
        Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.SECONDARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        // Stable sorting preserves the native order of equivalent display names.
        languages.sort((a, b) -> collator.compare(a.universalCaptions_getCaptionLabel().toString().trim(),
                b.universalCaptions_getCaptionLabel().toString().trim()));
        for (int i = 0; i < positions.size(); i++) tracks.set(positions.get(i), languages.get(i));
        return tracks;
    }
}

/*
 * Copyright 2026 itolkien.
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import java.net.URI;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Extends a native translation list without selecting a caption track or changing caption visibility. */
public final class CaptionTranslationList {
    private CaptionTranslationList() { }

    /** Implemented by the native track class at patch time. */
    public interface CaptionTrack {
        String patch_getLanguageCode();
        String patch_getVssId();
        String patch_getCaptionUrl();
        CharSequence patch_getCaptionLabel();
        CaptionTrack patch_copyTranslation(String language, String url, String vss, CharSequence label);
    }

    // Curated targets; their presence in the menu does not guarantee server availability.
    private static final String[] TARGETS = ("af am ar as az be bg bho bn bs ca ceb co cs cy da de " +
            "dv el en eo es et eu fa fi fil fr fy ga gd gl gu ha haw he hi hmn hr ht hu hy " +
            "id ig is it ja jv ka kk km kn ko ku ky la lb lo lt lv mg mi mk ml mn mr ms " +
            "mt my ne nl no ny or pa pl ps pt ro ru rw sd si sk sl sm sn so sq sr st su " +
            "sv sw ta te tg th tk tr tt ug uk ur uz vi xh yi yo zh-Hans zh-Hant zu").split(" ");

    public static List<CaptionTrack> augment(List<CaptionTrack> tracks, Locale locale) {
        if (tracks == null || tracks.isEmpty()) return tracks;
        List<CaptionTrack> result = new ArrayList<>(tracks);
        Set<String> represented = new HashSet<>();
        CaptionTrack prototype = null;
        for (CaptionTrack track : tracks) {
            if (track == null) continue;
            represented.add(languageKey(track.patch_getLanguageCode()));
            if (prototype == null && isTranslationPrototype(track)) prototype = track;
        }
        if (prototype != null) {
            String oldLanguage = prototype.patch_getLanguageCode();
            String oldUrl = prototype.patch_getCaptionUrl();
            String oldVss = prototype.patch_getVssId();
            String vssPrefix = oldVss.substring(0, oldVss.length() - oldLanguage.length());
            for (String code : TARGETS) {
                if (!represented.add(languageKey(code))) continue;
                String name = Locale.forLanguageTag(code).getDisplayName(locale);
                if (name.isEmpty()) name = code;
                result.add(prototype.patch_copyTranslation(code,
                        translatedUrl(oldUrl, oldLanguage, code),
                        vssPrefix + code, name));
            }
        }
        return sorted(result, locale);
    }

    private static String translatedUrl(String url, String oldLanguage, String code) {
        int start = url.indexOf('?') + 1;
        int fragment = url.indexOf('#', start);
        int end = fragment < 0 ? url.length() : fragment;
        String query = url.substring(start, end);
        return url.substring(0, start) + query.replaceFirst("(^|&)tlang=" + oldLanguage + "(?=&|$)",
                "$1tlang=" + code) + url.substring(end);
    }

    private static boolean isTranslationPrototype(CaptionTrack track) {
        String code = track.patch_getLanguageCode();
        String vss = track.patch_getVssId();
        if (code == null || !code.matches("[a-z]{2,8}(-[A-Za-z0-9]{2,8})*") ||
                vss == null || !vss.startsWith("t") || !vss.endsWith("." + code)) return false;
        try {
            URI uri = URI.create(track.patch_getCaptionUrl());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                    !("youtube.com".equalsIgnoreCase(host) || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com")) ||
                    uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) ||
                    !"/api/timedtext".equals(uri.getRawPath()) || uri.getRawQuery() == null) return false;
            int languageParameters = 0;
            for (String parameter : uri.getRawQuery().split("&", -1)) {
                if (!parameter.startsWith("tlang=")) continue;
                languageParameters++;
                if (!parameter.equals("tlang=" + code)) return false;
            }
            return languageParameters == 1;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }

    private static String languageKey(String raw) {
        if (raw == null) return "";
        String code = raw.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        String primary = code.split("-")[0];
        switch (primary) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            case "tl": return "fil";
            case "nb": return "no";
            case "zh":
                if (code.contains("-hant")) return "zh-hant";
                if (code.contains("-hans")) return "zh-hans";
                if (code.endsWith("-tw") || code.endsWith("-hk")) return "zh-hant";
                if (code.endsWith("-cn") || code.endsWith("-sg")) return "zh-hans";
                return code;
            default: return primary;
        }
    }

    private static List<CaptionTrack> sorted(List<CaptionTrack> tracks, Locale locale) {
        List<Integer> positions = new ArrayList<>();
        List<CaptionTrack> languages = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            CaptionTrack track = tracks.get(i);
            if (track == null) continue;
            String code = track.patch_getLanguageCode();
            CharSequence label = track.patch_getCaptionLabel();
            if (code == null || code.isEmpty() || code.endsWith("_OPTION") ||
                    label == null || label.toString().trim().isEmpty()) continue;
            positions.add(i);
            languages.add(track);
        }
        Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.SECONDARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        // Stable sorting preserves the native order of equivalent display names.
        languages.sort((a, b) -> collator.compare(a.patch_getCaptionLabel().toString().trim(),
                b.patch_getCaptionLabel().toString().trim()));
        for (int i = 0; i < positions.size(); i++) tracks.set(positions.get(i), languages.get(i));
        return tracks;
    }
}

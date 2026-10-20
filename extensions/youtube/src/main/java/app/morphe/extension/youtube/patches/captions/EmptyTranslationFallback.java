/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;

/** Per-manager, current-audio opt-in fallback. Never stores state or selects/enables a caption. */
public final class EmptyTranslationFallback {
    private EmptyTranslationFallback() { }
    private static final String AUTO = "AUTO_TRANSLATE_CAPTIONS_OPTION";

    /** Implemented by the native list model; getters bypass the fallback hooks. */
    public interface CaptionSource {
        List<CaptionTrack> universalCaptions_nativeDirect(boolean bypassFallback);
        List<CaptionTrack> universalCaptions_nativeTranslated(boolean bypassFallback);
        CaptionTrack universalCaptions_autoTranslateOption();
        List<String> universalCaptions_targetLanguages(boolean bypassFallback);
        CaptionTrack universalCaptions_defaultSource(boolean bypassFallback);
        CaptionTrack universalCaptions_eligibleSource(boolean bypassFallback);
    }

    public static List<CaptionTrack> direct(List<CaptionTrack> tracks, CaptionSource source, Locale locale) {
        if (tracks == null || source == null || locale == null) return tracks;
        try {
            for (CaptionTrack track : tracks) {
                if (track != null && AUTO.equals(track.universalCaptions_getLanguageCode())) return tracks;
            }
            // Only check whether a target exists; don't construct and sort 156 throwaway tracks.
            List<CaptionTrack> translated = source.universalCaptions_nativeTranslated(true);
            if (translated == null || !translated.isEmpty()) return tracks;
            OriginalCaptionSource candidate = selectSource(tracks, source);
            if (candidate == null) return tracks;
            boolean hasTarget = CaptionTranslationList.targets(source.universalCaptions_targetLanguages(true))
                    .stream().anyMatch(candidate::canTranslate);
            if (!hasTarget) return tracks;
            // Use YouTube's own localized menu row, including its native option flags.
            CaptionTrack option = source.universalCaptions_autoTranslateOption();
            if (option == null || !AUTO.equals(option.universalCaptions_getLanguageCode())) return tracks;
            List<CaptionTrack> result = new ArrayList<>(tracks);
            result.add(option);
            return result;
        } catch (RuntimeException | LinkageError ignored) {
            return tracks;
        }
    }

    public static List<CaptionTrack> translated(List<CaptionTrack> tracks, CaptionSource source, Locale locale) {
        if (tracks == null || source == null || locale == null) return tracks;
        try {
            List<String> nativeTargets = source.universalCaptions_targetLanguages(true);
            if (!tracks.isEmpty())
                return CaptionTranslationList.augment(tracks, locale, nativeTargets,
                        () -> selectSource(source.universalCaptions_nativeDirect(true), source));
            OriginalCaptionSource candidate = selectSource(
                    source.universalCaptions_nativeDirect(true), source);
            if (candidate == null) return tracks;
            List<CaptionTrack> result = CaptionTranslationList.fromSource(candidate, locale, nativeTargets);
            return result.isEmpty() ? tracks : result;
        } catch (RuntimeException | LinkageError ignored) {
            return tracks;
        }
    }

    /** Audio-specific indices take precedence; an invalid native result is never guessed over. */
    private static OriginalCaptionSource selectSource(List<CaptionTrack> direct, CaptionSource source) {
        CaptionTrack eligible = source.universalCaptions_eligibleSource(true);
        return eligible != null ? OriginalCaptionSource.from(eligible) : candidate(direct, source);
    }

    private static OriginalCaptionSource candidate(List<CaptionTrack> tracks, CaptionSource source) {
        if (tracks == null) return null;
        OriginalCaptionSource first = null, matchedDefault = null;
        CaptionTrack nativeDefault = source.universalCaptions_defaultSource(true);
        String video = null, firstVss = null, firstUrl = null;
        boolean mixedSources = false;
        for (CaptionTrack track : tracks) {
            if (track == null) return null;
            String language = track.universalCaptions_getLanguageCode();
            if ("DISABLE_CAPTIONS_OPTION".equals(language) || AUTO.equals(language)) continue;
            OriginalCaptionSource original =
                    OriginalCaptionSource.from(track);
            if (original == null || video != null && !video.equals(original.videoId()))
                return null;
            video = original.videoId();
            String vss = track.universalCaptions_getVssId();
            if (first == null) {
                first = original;
                firstVss = vss;
                firstUrl = track.universalCaptions_getCaptionUrl();
            } else if (!firstVss.equals(vss)
                    || !firstUrl.equals(track.universalCaptions_getCaptionUrl())) mixedSources = true;
            if (nativeDefault != null && matchedDefault == null
                    && vss.equals(nativeDefault.universalCaptions_getVssId())
                    && track.universalCaptions_getCaptionUrl().equals(nativeDefault.universalCaptions_getCaptionUrl())) {
                matchedDefault = original;
            }
        }
        // Native caption eligibility is track-specific, even within the same language.
        return matchedDefault != null ? matchedDefault : mixedSources ? null : first;
    }
}

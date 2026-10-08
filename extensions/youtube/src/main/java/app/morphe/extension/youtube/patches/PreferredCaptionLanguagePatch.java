/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PreferredCaptionLanguagePatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface SubtitleManagerInterface {
        // Methods are added during patching.

        /**
         * @return Direct caption tracks, including the disable and auto-translate pseudo options.
         *         Null if no video is loaded.
         */
        @Nullable
        List<CaptionTrackInterface> patch_getDirectCaptionTracks();

        /**
         * @return Auto-translated caption tracks. Null if no video is loaded.
         */
        @Nullable
        List<CaptionTrackInterface> patch_getAutoTranslateCaptionTracks();
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface CaptionTrackInterface {
        // Methods are added during patching.

        /**
         * @return Language code (e.g. "ko", "en"), or a pseudo option such as {@link #DISABLE_OPTION}.
         */
        String patch_getLanguageCode();

        /**
         * @return Track vss id (e.g. ".ko", "a.en", "t.ko"), or "-" for the disable option.
         */
        String patch_getVssId();
    }

    private static final String DISABLE_OPTION = "DISABLE_CAPTIONS_OPTION";
    private static final String AUTO_TRANSLATE_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION";
    private static final String PREFERRED_TRACK_SELECT_TYPE = "PREFERRED_TRACK";

    /**
     * Time after a new video starts where explicit track selections are not considered user selections.
     */
    private static final long USER_SELECTION_GRACE_PERIOD_MILLISECONDS = 500;

    private static final AtomicBoolean userSelectedTrack = new AtomicBoolean(false);
    private static final AtomicBoolean userInteractionAllowed = new AtomicBoolean(false);
    private static volatile long videoStartTime;

    @Nullable
    private static volatile String lastProgrammaticTrackLang;
    @Nullable
    private static volatile String lastProgrammaticTrackVss;

    /**
     * Injection point: Called after SubtitleManager.getDefaultCaptionTrack() at caller-site.
     */
    public static CaptionTrackInterface getPreferredCaptionTrack(
            SubtitleManagerInterface subtitleManager, @Nullable CaptionTrackInterface originalTrack) {
        try {
            // CC off guard: Preserve explicitly disabled caption option.
            if (isExplicitlyDisabled(originalTrack)) {
                return originalTrack;
            }

            String prefLang = Settings.PREFERRED_CAPTION_LANGUAGE.get();
            Logger.printDebug(() -> "getPreferredCaptionTrack orig: " + originalTrack + " prefLang: " + prefLang);

            if ("off".equalsIgnoreCase(prefLang)) {
                return originalTrack;
            }

            if (userSelectedTrack.get()) {
                Logger.printDebug(() -> "User manually selected caption track, preserving: " + originalTrack);
                return originalTrack;
            }

            String targetLang = normalizeLanguageCode("default".equalsIgnoreCase(prefLang)
                    ? getAppLanguage()
                    : prefLang);
            if (targetLang.isEmpty()) {
                return originalTrack;
            }

            // Priority 1: Direct tracks (provider subtitle, or native language subtitle).
            CaptionTrackInterface nativeTrack = null;
            boolean autoTranslateAvailable = false;

            List<CaptionTrackInterface> directTracks = subtitleManager.patch_getDirectCaptionTracks();
            if (directTracks != null) {
                for (CaptionTrackInterface track : directTracks) {
                    String lang = track.patch_getLanguageCode();

                    if (AUTO_TRANSLATE_OPTION.equals(lang)) {
                        autoTranslateAvailable = true;
                        continue;
                    }

                    if (DISABLE_OPTION.equals(lang) || !matchesLanguage(lang, targetLang)) {
                        continue;
                    }

                    if (track.patch_getVssId().startsWith(".")) {
                        // Creator / provider subtitle in target language has the highest priority.
                        Logger.printDebug(() -> "Selected priority 1 (provider subtitle): " + track);
                        return recordProgrammaticSelection(track);
                    }

                    if (nativeTrack == null) {
                        // Native language track (video audio is in target language).
                        nativeTrack = track;
                    }
                }
            }

            if (nativeTrack != null) {
                CaptionTrackInterface selectedTrack = nativeTrack;
                Logger.printDebug(() -> "Selected priority 1b (native target language track): " + selectedTrack);
                return recordProgrammaticSelection(nativeTrack);
            }

            // Priority 2: Auto-translate to target language.
            // Only evaluate if YouTube actually offers auto-translation on this video.
            if (autoTranslateAvailable) {
                List<CaptionTrackInterface> autoTracks = subtitleManager.patch_getAutoTranslateCaptionTracks();
                if (autoTracks != null) {
                    for (CaptionTrackInterface track : autoTracks) {
                        if (matchesLanguage(track.patch_getLanguageCode(), targetLang)) {
                            Logger.printDebug(() -> "Selected priority 2 (auto-translated subtitle): " + track);
                            return recordProgrammaticSelection(track);
                        }
                    }
                }
            } else {
                Logger.printDebug(() -> "Auto-translation not available for this video, skipping priority 2");
            }

            // Priority 3: Fallback to the original YouTube selection.
            Logger.printDebug(() -> "Falling back to original track: " + originalTrack);
            return recordProgrammaticSelection(originalTrack);
        } catch (Exception ex) {
            Logger.printException(() -> "getPreferredCaptionTrack failure", ex);
            return originalTrack;
        }
    }

    /**
     * Injection point: SubtitleManager.setSubtitleTrack(SubtitleTrack, SelectType, ...)
     * Intercepts and overrides the subtitle track with preferred language if applicable.
     */
    public static CaptionTrackInterface onSetSubtitleTrack(SubtitleManagerInterface subtitleManager,
                                                           @Nullable CaptionTrackInterface track,
                                                           @Nullable Enum<?> selectType) {
        try {
            final boolean interactionAllowed = userInteractionAllowed.get();
            Logger.printDebug(() -> "onSetSubtitleTrack track: " + track
                    + " selectType: " + selectType
                    + " interactionAllowed: " + interactionAllowed
                    + " userSelectedTrack: " + userSelectedTrack.get());

            // Respect explicit user selection only after the initial video load window.
            if (interactionAllowed
                    && selectType != null && PREFERRED_TRACK_SELECT_TYPE.equals(selectType.name())
                    && System.currentTimeMillis() - videoStartTime > USER_SELECTION_GRACE_PERIOD_MILLISECONDS) {
                if (isSameTrack(track, lastProgrammaticTrackLang, lastProgrammaticTrackVss)) {
                    Logger.printDebug(() -> "Ignoring PREFERRED_TRACK as it matches programmatic selection");
                    return track;
                }

                userSelectedTrack.set(true);
                Logger.printDebug(() -> "User explicitly selected subtitle track: " + track);
                return track;
            }

            if (userSelectedTrack.get()) {
                Logger.printDebug(() -> "User manually selected caption track previously, preserving: " + track);
                return track;
            }

            if (track == null || isExplicitlyDisabled(track)) {
                Logger.printDebug(() -> "Track is disable option or null, preserving: " + track);
                return track;
            }

            CaptionTrackInterface preferred = getPreferredCaptionTrack(subtitleManager, track);
            if (preferred != null) {
                Logger.printDebug(() -> "Overriding subtitle track with preferred track: " + preferred);
                return preferred;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSetSubtitleTrack failure", ex);
        }

        return track;
    }

    /**
     * Injection point: Video start hook.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredController) {
        userInteractionAllowed.set(false);
        userSelectedTrack.set(false);
        recordProgrammaticSelection(null);
        videoStartTime = System.currentTimeMillis();
        Logger.printDebug(() -> "newVideoStarted, user interaction locked startTime: " + videoStartTime);
    }

    /**
     * Injection point: Video information loaded hook.
     */
    public static void videoInformationLoaded() {
        Logger.printDebug(() -> "videoInformationLoaded, scheduling 300ms unlock");
        Utils.runOnMainThreadDelayed(() -> {
            userInteractionAllowed.set(true);
            Logger.printDebug(() -> "User interaction unlocked");
        }, 300);
    }

    private static String getAppLanguage() {
        Locale appLocale = Requester.getAppLocale();
        String tag = appLocale.toLanguageTag();
        return !tag.isEmpty() && !"und".equalsIgnoreCase(tag)
                ? tag
                : appLocale.getLanguage();
    }

    private static boolean isExplicitlyDisabled(@Nullable CaptionTrackInterface track) {
        if (track == null) return false;
        String lang = track.patch_getLanguageCode();
        return lang.isEmpty() || DISABLE_OPTION.equals(lang) || "-".equals(track.patch_getVssId());
    }

    /**
     * @return The track parameter.
     */
    @Nullable
    private static CaptionTrackInterface recordProgrammaticSelection(@Nullable CaptionTrackInterface track) {
        if (track != null) {
            lastProgrammaticTrackLang = track.patch_getLanguageCode();
            lastProgrammaticTrackVss = track.patch_getVssId();
        } else {
            lastProgrammaticTrackLang = null;
            lastProgrammaticTrackVss = null;
        }
        return track;
    }

    private static boolean isSameTrack(@Nullable CaptionTrackInterface track,
                                       @Nullable String targetLang, @Nullable String targetVss) {
        if (track == null) return false;
        String vss = track.patch_getVssId();
        final boolean hasTargetVss = targetVss != null && !targetVss.isEmpty();

        if (hasTargetVss && targetVss.equals(vss)) {
            return true;
        }

        // Language match is only sufficient if one of the tracks has no vss id.
        return targetLang != null && !targetLang.isEmpty()
                && targetLang.equalsIgnoreCase(track.patch_getLanguageCode())
                && (!hasTargetVss || vss.isEmpty());
    }

    private static String normalizeLanguageCode(@Nullable String code) {
        if (code == null) return "";
        String s = code.trim().toLowerCase(Locale.ROOT).replace('_', '-');

        // Legacy ISO 639 codes used by Java Locale and YouTube.
        if (s.equals("iw") || s.startsWith("iw-")) {
            return "he" + s.substring(2);
        }
        if (s.equals("in") || s.startsWith("in-")) {
            return "id" + s.substring(2);
        }
        if (s.equals("ji") || s.startsWith("ji-")) {
            return "yi" + s.substring(2);
        }
        return s;
    }

    private static boolean matchesLanguage(@Nullable String trackLang, String targetLang) {
        if (trackLang == null) return false;
        String t1 = normalizeLanguageCode(trackLang);
        String t2 = normalizeLanguageCode(targetLang);
        if (t1.equals(t2) || t1.startsWith(t2 + "-") || t2.startsWith(t1 + "-")) {
            return true;
        }

        // Prevent cross-matching between Simplified and Traditional Chinese.
        final boolean t1Traditional = isTraditionalChinese(t1);
        final boolean t1Simplified = isSimplifiedChinese(t1);
        final boolean t2Traditional = isTraditionalChinese(t2);
        final boolean t2Simplified = isSimplifiedChinese(t2);
        if ((t1Simplified && t2Traditional) || (t1Traditional && t2Simplified)) {
            return false;
        }

        return getPrimaryLanguage(t1).equals(getPrimaryLanguage(t2));
    }

    private static boolean isTraditionalChinese(String code) {
        return code.contains("hant") || code.contains("tw") || code.contains("hk");
    }

    private static boolean isSimplifiedChinese(String code) {
        return code.contains("hans") || code.contains("cn");
    }

    private static String getPrimaryLanguage(String code) {
        final int dashIndex = code.indexOf('-');
        return dashIndex < 0 ? code : code.substring(0, dashIndex);
    }
}

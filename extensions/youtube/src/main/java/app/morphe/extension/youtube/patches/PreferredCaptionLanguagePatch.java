/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PreferredCaptionLanguagePatch {
    private static final String DISABLE_OPTION = "DISABLE_CAPTIONS_OPTION";
    private static final String AUTO_TRANSLATE_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION";

    private static final AtomicBoolean userSelectedTrack = new AtomicBoolean(false);
    private static final AtomicBoolean userInteractionAllowed = new AtomicBoolean(false);
    private static volatile long videoStartTime = 0;

    private static volatile String lastProgrammaticTrackLang = null;
    private static volatile String lastProgrammaticTrackVss = null;

    // Reflection caches
    private static volatile boolean reflectionInitialized = false;
    private static Field captionTracksManagerField = null; // Field on SubtitleManager
    private static Method directTracksMethod = null;        // returns List of direct tracks on CaptionTracksManager
    private static Method autoTranslateTracksMethod = null; // returns List of auto-translated tracks on CaptionTracksManager
    private static Method listMethod1 = null;
    private static Method listMethod2 = null;

    private static volatile boolean trackFieldsInitialized = false;
    private static Field trackLanguageField = null; // Field on CaptionTrack (e.g. "ko", "en")
    private static Field trackVssIdField = null;    // Field on CaptionTrack (e.g. ".ko", "a.en", "t.ko")

    /**
     * Injection point: Called after SubtitleManager.getDefaultCaptionTrack() at caller-site.
     */
    public static Object getPreferredCaptionTrack(Object subtitleManager, Object originalTrack) {
        try {
            if (subtitleManager == null) {
                return originalTrack;
            }

            // CC off guard: Preserve explicitly disabled caption option
            if (isExplicitlyDisabled(originalTrack)) {
                return originalTrack;
            }

            String prefLang = Settings.PREFERRED_CAPTION_LANGUAGE.get();
            final Object effectiveSm = subtitleManager;
            final Object effectiveOrig = originalTrack;
            final String effectivePrefLang = prefLang;
            Logger.printDebug(() -> "PreferredCaptionLanguagePatch: getPreferredCaptionTrack entry, sm=" + (effectiveSm != null) + ", orig=" + effectiveOrig + ", prefLang=" + effectivePrefLang);

            if (prefLang == null || "off".equalsIgnoreCase(prefLang)) {
                return originalTrack;
            }

            if (userSelectedTrack.get()) {
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: User manually selected caption track, preserving: " + effectiveOrig);
                return originalTrack;
            }

            String targetLang;
            if ("default".equalsIgnoreCase(prefLang) || "app".equalsIgnoreCase(prefLang)) {
                Locale appLocale = Requester.getAppLocale();
                String tag = appLocale.toLanguageTag();
                targetLang = (tag != null && !tag.isEmpty() && !"und".equalsIgnoreCase(tag))
                        ? tag
                        : appLocale.getLanguage();
            } else {
                targetLang = prefLang;
            }

            targetLang = normalizeLanguageCode(targetLang);
            if (targetLang.isEmpty()) {
                return originalTrack;
            }

            initReflection(subtitleManager);

            if (captionTracksManagerField == null) {
                return originalTrack;
            }

            Object tracksManager = captionTracksManagerField.get(subtitleManager);
            if (tracksManager == null) {
                return originalTrack;
            }

            resolveListMethods(tracksManager);

            // 1. Direct tracks (Priority 1: Provider subtitle, or Native language subtitle)
            Object providerTrack = null;
            Object nativeAsrTrack = null;
            boolean autoTranslateAvailable = false;

            if (directTracksMethod != null) {
                List<?> directTracks = (List<?>) directTracksMethod.invoke(tracksManager);
                if (directTracks != null) {
                    for (Object track : directTracks) {
                        if (track == null) continue;
                        initTrackFields(track);

                        String lang = getTrackLanguage(track);
                        String vssId = getTrackVssId(track);

                        if (AUTO_TRANSLATE_OPTION.equals(lang)) {
                            autoTranslateAvailable = true;
                            continue;
                        }

                        if (lang == null || isPseudoOption(lang)) {
                            continue;
                        }

                        if (matchesLanguage(lang, targetLang)) {
                            if (vssId != null && vssId.startsWith(".")) {
                                // Creator / Provider subtitle in target language!
                                providerTrack = track;
                                break;
                            } else if (nativeAsrTrack == null) {
                                // Native language track (video audio is in target language)
                                nativeAsrTrack = track;
                            }
                        }
                    }
                }
            }

            // Provider subtitle in target language has highest priority
            if (providerTrack != null) {
                final Object selectedProviderTrack = providerTrack;
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Selected Priority 1 (Provider subtitle): " + selectedProviderTrack);
                recordProgrammaticSelection(providerTrack);
                return providerTrack;
            }

            // If the video itself is in the target language (native ASR), keep native
            if (nativeAsrTrack != null) {
                final Object selectedNativeAsrTrack = nativeAsrTrack;
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Selected Priority 1b (Native target language track): " + selectedNativeAsrTrack);
                recordProgrammaticSelection(nativeAsrTrack);
                return nativeAsrTrack;
            }

            // 2. Auto-translated tracks (Priority 2: Auto-translate to target language)
            // Only evaluate if YouTube actually offers auto-translation on this video!
            if (autoTranslateAvailable && autoTranslateTracksMethod != null) {
                List<?> autoTracks = (List<?>) autoTranslateTracksMethod.invoke(tracksManager);
                if (autoTracks != null) {
                    for (Object track : autoTracks) {
                        if (track == null) continue;
                        initTrackFields(track);

                        String lang = getTrackLanguage(track);
                        if (lang != null && matchesLanguage(lang, targetLang)) {
                            final Object selectedAutoTrack = track;
                            Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Selected Priority 2 (Auto-translated subtitle): " + selectedAutoTrack);
                            recordProgrammaticSelection(track);
                            return track;
                        }
                    }
                }
            } else if (!autoTranslateAvailable) {
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Auto-translation not available for this video, skipping Priority 2");
            }

            // 3. Fallback: YouTube original selection
            final Object fallbackOrig = originalTrack;
            Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Falling back to original track: " + fallbackOrig);
            recordProgrammaticSelection(originalTrack);
            return originalTrack;
        } catch (Throwable t) {
            Logger.printException(() -> "PreferredCaptionLanguagePatch: Error getting preferred caption track", t);
            return originalTrack;
        }
    }

    /**
     * Injection point: SubtitleManager.setSubtitleTrack(SubtitleTrack, SelectType, ...)
     * Intercepts and overrides the subtitle track with preferred language if applicable.
     */
    public static Object onSetSubtitleTrack(Object subtitleManager, Object track, Object selectType) {
        try {
            final Object finalTrack = track;
            final Object finalSelectType = selectType;
            final boolean interactionAllowed = userInteractionAllowed.get();
            final boolean isUserLocked = userSelectedTrack.get();
            Logger.printDebug(() -> "PreferredCaptionLanguagePatch: onSetSubtitleTrack called: track=" + finalTrack
                    + ", selectType=" + finalSelectType
                    + ", interactionAllowed=" + interactionAllowed
                    + ", userSelectedTrack=" + isUserLocked);

            if (subtitleManager == null) {
                return track;
            }

            initReflection(subtitleManager);

            boolean isExplicitSelect = selectType != null && "PREFERRED_TRACK".equals(selectType.toString());

            // Respect explicit user selection only after the initial video load window
            if (interactionAllowed && (System.currentTimeMillis() - videoStartTime > 500)) {
                if (isExplicitSelect) {
                    if (isSameTrack(track, lastProgrammaticTrackLang, lastProgrammaticTrackVss)) {
                        Logger.printDebug(() -> "PreferredCaptionLanguagePatch: onSetSubtitleTrack ignoring PREFERRED_TRACK as it matches programmatic selection");
                        return track;
                    }
                    userSelectedTrack.set(true);
                    Logger.printDebug(() -> "PreferredCaptionLanguagePatch: User explicitly selected subtitle track: " + finalTrack);
                    return track;
                }
            }

            if (userSelectedTrack.get()) {
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: User manually selected caption track previously, preserving: " + finalTrack);
                return track;
            }

            if (isDisableTrack(track)) {
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Track is disable option or null, preserving: " + finalTrack);
                return track;
            }

            Object preferred = getPreferredCaptionTrack(subtitleManager, track);
            if (preferred != null) {
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Overriding subtitle track with preferred track: " + preferred);
                return preferred;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PreferredCaptionLanguagePatch: Error tracking subtitle track selection", t);
        }
        return track;
    }

    private static boolean isExplicitlyDisabled(Object track) {
        if (track == null) return false;
        String lang = getTrackLanguage(track);
        String vss = getTrackVssId(track);
        return DISABLE_OPTION.equals(lang) || "-".equals(vss) || (lang != null && lang.isEmpty());
    }

    private static boolean isDisableTrack(Object track) {
        if (track == null) return true;
        return isExplicitlyDisabled(track);
    }

    private static void recordProgrammaticSelection(Object track) {
        if (track != null) {
            lastProgrammaticTrackLang = getTrackLanguage(track);
            lastProgrammaticTrackVss = getTrackVssId(track);
        } else {
            lastProgrammaticTrackLang = null;
            lastProgrammaticTrackVss = null;
        }
    }

    private static boolean isSameTrack(Object track, String targetLang, String targetVss) {
        if (track == null || (targetLang == null && targetVss == null)) return false;
        String lang = getTrackLanguage(track);
        String vss = getTrackVssId(track);

        if (targetVss != null && !targetVss.isEmpty() && targetVss.equals(vss)) {
            return true;
        }
        if (targetLang != null && !targetLang.isEmpty() && targetLang.equalsIgnoreCase(lang)) {
            if (targetVss == null || targetVss.isEmpty() || vss == null || vss.isEmpty()) {
                return true;
            }
            return targetVss.equals(vss);
        }
        return false;
    }

    /**
     * Injection point: Video start hook
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredController) {
        userInteractionAllowed.set(false);
        userSelectedTrack.set(false);
        recordProgrammaticSelection(null);
        videoStartTime = System.currentTimeMillis();
        Logger.printDebug(() -> "PreferredCaptionLanguagePatch: newVideoStarted, user interaction locked, startTime=" + videoStartTime);
    }

    /**
     * Injection point: Video information loaded hook
     */
    public static void videoInformationLoaded() {
        Logger.printDebug(() -> "PreferredCaptionLanguagePatch: videoInformationLoaded called, scheduling 300ms unlock");
        Utils.runOnMainThreadDelayed(() -> {
            userInteractionAllowed.set(true);
            Logger.printDebug(() -> "PreferredCaptionLanguagePatch: videoInformationLoaded delayed runnable executed, user interaction unlocked");
        }, 300);
    }

    private static synchronized void initReflection(Object subtitleManager) {
        if (reflectionInitialized) return;
        try {
            Class<?> smClass = subtitleManager.getClass();
            for (Field field : smClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                Method directM = null;
                Method autoM = null;
                for (Method m : type.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (List.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 0) {
                        if (directM == null) {
                            directM = m;
                        } else if (autoM == null) {
                            autoM = m;
                        }
                    }
                }

                if (directM != null && autoM != null) {
                    field.setAccessible(true);
                    captionTracksManagerField = field;

                    directM.setAccessible(true);
                    autoM.setAccessible(true);

                    listMethod1 = directM;
                    listMethod2 = autoM;

                    Object managerObj = field.get(subtitleManager);
                    if (managerObj != null) {
                        resolveListMethods(managerObj);
                    }
                    reflectionInitialized = true;
                    Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Initialized CaptionTracksManager reflection: " + field.getName());
                    break;
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PreferredCaptionLanguagePatch: Failed to initialize SubtitleManager reflection", t);
        }
    }

    private static synchronized void resolveListMethods(Object managerObj) {
        if (directTracksMethod != null && autoTranslateTracksMethod != null) return;
        if (managerObj == null || listMethod1 == null || listMethod2 == null) return;

        try {
            List<?> l1 = (List<?>) listMethod1.invoke(managerObj);
            List<?> l2 = (List<?>) listMethod2.invoke(managerObj);

            if (containsDisableOption(l1)) {
                directTracksMethod = listMethod1;
                autoTranslateTracksMethod = listMethod2;
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Resolved directTracksMethod=" + listMethod1.getName() + ", autoTranslateTracksMethod=" + listMethod2.getName());
            } else if (containsDisableOption(l2)) {
                directTracksMethod = listMethod2;
                autoTranslateTracksMethod = listMethod1;
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Resolved directTracksMethod=" + listMethod2.getName() + ", autoTranslateTracksMethod=" + listMethod1.getName());
            } else if (containsAutoTranslateTrack(l1)) {
                directTracksMethod = listMethod2;
                autoTranslateTracksMethod = listMethod1;
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Resolved via t.: directTracksMethod=" + listMethod2.getName() + ", autoTranslateTracksMethod=" + listMethod1.getName());
            } else if (containsAutoTranslateTrack(l2)) {
                directTracksMethod = listMethod1;
                autoTranslateTracksMethod = listMethod2;
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Resolved via t.: directTracksMethod=" + listMethod1.getName() + ", autoTranslateTracksMethod=" + listMethod2.getName());
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PreferredCaptionLanguagePatch: Failed resolving list methods", t);
        }
    }

    private static boolean containsAutoTranslateTrack(List<?> list) {
        if (list == null || list.isEmpty()) return false;
        for (Object item : list) {
            if (item == null) continue;
            String vss = getTrackVssId(item);
            if (vss != null && (vss.startsWith("t.") || vss.startsWith("ta."))) return true;
        }
        return false;
    }

    private static boolean containsDisableOption(List<?> list) {
        if (list == null || list.isEmpty()) return false;
        for (Object item : list) {
            if (item == null) continue;
            for (Field f : item.getClass().getDeclaredFields()) {
                if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(item);
                        if (DISABLE_OPTION.equals(val) || AUTO_TRANSLATE_OPTION.equals(val)) {
                            initTrackFields(item);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return false;
    }

    private static synchronized void initTrackFields(Object track) {
        if (trackFieldsInitialized) return;
        try {
            Class<?> clazz = track.getClass();
            Field langF = null;
            Field vssF = null;

            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(track);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (DISABLE_OPTION.equals(s) || AUTO_TRANSLATE_OPTION.equals(s)) {
                            langF = f;
                        } else if ("-".equals(s) || s.startsWith(".") || s.startsWith("a.") || s.startsWith("t.") || s.startsWith("ta.")) {
                            vssF = f;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (vssF != null && langF == null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class || f.equals(vssF)) continue;
                    f.setAccessible(true);
                    try {
                        Object val = f.get(track);
                        if (val instanceof String) {
                            String s = (String) val;
                            // Strictly match ISO 639-1/2 or BCP-47 tag (e.g. en, ko, zh-Hans). Never match languageName (e.g. English, Korean).
                            if (s.matches("(?i)^[a-z]{2,3}(-[a-zA-Z0-9]+)?$") && !s.contains(" ") && !s.contains("/")) {
                                langF = f;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (langF != null) trackLanguageField = langF;
            if (vssF != null) trackVssIdField = vssF;

            // Only mark fully initialized when both are safely found
            if (trackLanguageField != null && trackVssIdField != null) {
                trackFieldsInitialized = true;
                final String langName = trackLanguageField.getName();
                final String vssName = trackVssIdField.getName();
                Logger.printDebug(() -> "PreferredCaptionLanguagePatch: Initialized CaptionTrack fields: lang=" + langName + ", vss=" + vssName);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "PreferredCaptionLanguagePatch: Failed to initialize track fields", t);
        }
    }

    private static String getTrackLanguage(Object track) {
        if (track == null) return null;
        if (trackLanguageField != null) {
            try {
                Object val = trackLanguageField.get(track);
                if (val instanceof String) return (String) val;
            } catch (Throwable ignored) {}
        }
        for (Field f : track.getClass().getDeclaredFields()) {
            if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    String s = (String) f.get(track);
                    if (s != null && !s.isEmpty() && !s.startsWith(".") && !s.startsWith("a.") && !s.startsWith("t.") && !s.startsWith("ta.") && !"-".equals(s) && !s.contains("&tlang=")) {
                        return s;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static String getTrackVssId(Object track) {
        if (track == null) return null;
        if (trackVssIdField != null) {
            try {
                Object val = trackVssIdField.get(track);
                if (val instanceof String) return (String) val;
            } catch (Throwable ignored) {}
        }
        for (Field f : track.getClass().getDeclaredFields()) {
            if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    String s = (String) f.get(track);
                    if (s != null && (s.startsWith(".") || s.startsWith("a.") || s.startsWith("t.") || s.startsWith("ta.") || "-".equals(s))) {
                        return s;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean isPseudoOption(String lang) {
        return DISABLE_OPTION.equals(lang) || AUTO_TRANSLATE_OPTION.equals(lang);
    }

    private static String normalizeLanguageCode(String code) {
        if (code == null) return "";
        String s = code.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (s.equals("iw") || s.startsWith("iw-")) {
            s = "he" + s.substring(2);
        } else if (s.equals("in") || s.startsWith("in-")) {
            s = "id" + s.substring(2);
        } else if (s.equals("ji") || s.startsWith("ji-")) {
            s = "yi" + s.substring(2);
        }
        return s;
    }

    private static boolean matchesLanguage(String trackLang, String targetLang) {
        if (trackLang == null || targetLang == null) return false;
        String t1 = normalizeLanguageCode(trackLang);
        String t2 = normalizeLanguageCode(targetLang);
        if (t1.equals(t2)) return true;
        if (t1.startsWith(t2 + "-") || t2.startsWith(t1 + "-")) return true;

        // Prevent cross-matching between Simplified and Traditional Chinese
        boolean t1Traditional = t1.contains("hant") || t1.contains("tw") || t1.contains("hk");
        boolean t1Simplified = t1.contains("hans") || t1.contains("cn");
        boolean t2Traditional = t2.contains("hant") || t2.contains("tw") || t2.contains("hk");
        boolean t2Simplified = t2.contains("hans") || t2.contains("cn");

        if ((t1Simplified && t2Traditional) || (t1Traditional && t2Simplified)) {
            return false;
        }

        String p1 = t1.contains("-") ? t1.substring(0, t1.indexOf('-')) : t1;
        String p2 = t2.contains("-") ? t2.substring(0, t2.indexOf('-')) : t2;
        return p1.equals(p2);
    }
}

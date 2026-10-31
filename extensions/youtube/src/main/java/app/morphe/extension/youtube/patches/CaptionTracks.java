/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import org.w3c.dom.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Captures the caption source selected by the native player, and binds the caption
 * timelines of that source to a single {@link CaptionSession}.
 */
@SuppressWarnings("unused")
public final class CaptionTracks {
    /**
     * Number of timelines kept bound to a session. The native player caches recent timelines.
     */
    private static final int MAX_BOUND_STATES = 4;

    private static final ThreadLocal<Track> parsing = new ThreadLocal<>();
    private static final List<WeakReference<StableCaptionBlocks.State>> translatedStates = new ArrayList<>();

    @Nullable
    private static String video, source, selectedInputLanguage;
    @Nullable
    private static String requestedSource, requestedTarget;
    private static boolean selectionDisabled;

    @Nullable
    private static CaptionSession translatedSession;
    @Nullable
    private static StableCaptionBlockPolicy translatedPolicy;
    @Nullable
    private static String translatedKey;
    @Nullable
    private static volatile Object translatedToken;

    private CaptionTracks() {}

    private static final class Track {
        final String source, language, video;
        @Nullable
        final String secondary;
        final boolean automatic, translated;

        Track(String source, String language, @Nullable String secondary, String video,
              boolean automatic, boolean translated) {
            this.source = source;
            this.language = language;
            this.secondary = secondary;
            this.video = video;
            this.automatic = automatic;
            this.translated = translated;
        }

        Track withSecondary(@Nullable String secondary) {
            return new Track(source, language, secondary, video, automatic, translated);
        }
    }

    static final class Captured {
        final Track track;
        final StableCaptionBlockPolicy policy;

        Captured(Track track, StableCaptionBlockPolicy policy) {
            this.track = track;
            this.policy = policy;
        }
    }

    /**
     * @return If a secondary language is selected, whether the captions menu toggle is on or off.
     */
    static boolean hasSecondaryLanguage() {
        return StableCaptionBlocks.isEnabled()
                && AutoTranslateCaptionsPatch.translationLanguage(Settings.SECONDARY_CAPTION_LANGUAGE.get()) != null;
    }

    static boolean dualCaptionEnabled() {
        return Settings.DUAL_CAPTIONS_ENABLED.get() && hasSecondaryLanguage();
    }

    @Nullable
    private static String secondaryLanguage() {
        return dualCaptionEnabled()
                ? AutoTranslateCaptionsPatch.getLanguageKey(Settings.SECONDARY_CAPTION_LANGUAGE.get())
                : null;
    }

    /**
     * Applies a change of the captions menu toggle.
     * The native selection is applied again, but a cached original track may not load again.
     */
    static void setDualCaptionsEnabled(boolean enabled) {
        long playbackTime = Utils.isCurrentlyOnMainThread() ? VideoInformation.getVideoTime() : -1;
        Map<StableCaptionBlocks.State, Long> cached = new LinkedHashMap<>();
        synchronized (CaptionTracks.class) {
            if (!enabled && translatedSession != null && source != null
                    && !translatedSession.primaryLanguage.equals(translatedSession.sourceLanguage)) {
                requestedSource = source;
                requestedTarget = translatedSession.primaryLanguage;
            }
            Settings.DUAL_CAPTIONS_ENABLED.save(enabled);
            for (WeakReference<StableCaptionBlocks.State> reference : translatedStates) {
                StableCaptionBlocks.State state = reference.get();
                if (state != null && state.captured != null && state.translation != null) {
                    cached.put(state, playbackTime >= 0 ? playbackTime : state.translation.playbackTime());
                }
            }
            invalidateTranslation();
        }

        PreferredCaptionLanguagePatch.refreshCaptionSelection();

        synchronized (CaptionTracks.class) {
            for (Map.Entry<StableCaptionBlocks.State, Long> entry : cached.entrySet()) {
                StableCaptionBlocks.State state = entry.getKey();
                Captured captured = state.captured;
                Track previous = captured.track;
                if (!isCurrent(previous) || (enabled && previous.translated)) continue;

                Track next = previous.withSecondary(secondaryLanguage());
                state.captured = new Captured(next, captured.policy);
                attachTranslation(next, captured.policy, state);
                state.translation.demand(entry.getValue());
            }
            translationChanged(translatedToken);
            for (StableCaptionBlocks.State state : cached.keySet()) {
                StableCaptionBlocks.requestLayout(state);
            }
        }
    }

    static synchronized boolean isCurrentVideo(@Nullable String id) {
        return id != null && id.equals(video);
    }

    /**
     * @return Validated identity shared by the original and the translated forms of a source.
     */
    @Nullable
    static String sourceIdentity(@Nullable AutoTranslateCaptionsPatch.CaptionTrackInterface track) {
        if (track == null) return null;

        Map<String, String> fields = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
        if (fields == null
                || fields.get("v") == null
                || !fields.get("v").matches("[A-Za-z0-9_-]{11}")
                || fields.get("lang") == null
                || !fields.get("lang").matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})*")) {
            return null;
        }

        String vss = track.patch_getVssId();
        if (vss == null) return null;

        String translated = fields.get("tlang");
        if (translated != null) {
            if (AutoTranslateCaptionsPatch.translationLanguage(translated) == null
                    || !translated.equals(track.patch_getLanguageCode())
                    || !vss.startsWith("t")
                    || !vss.endsWith("." + translated)) {
                return null;
            }
            vss = vss.substring(1, vss.length() - translated.length() - 1);
        } else if (!AutoTranslateCaptionsPatch.getLanguageKey(fields.get("lang"))
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode()))) {
            return null;
        }
        if (!vss.matches("(?:a)?\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) return null;

        final boolean asr = vss.startsWith("a.");
        String vssLanguage = vss.substring(asr ? 2 : 1).split("\\.", 2)[0];
        if (!AutoTranslateCaptionsPatch.getLanguageKey(vssLanguage)
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(fields.get("lang")))) {
            return null;
        }
        String kind = fields.get("kind");
        if (asr ? !"asr".equals(kind) : kind != null && !kind.isEmpty()) return null;

        return fields.get("v") + "|" + vss + "|" + fields.get("lang") + "|" + fields.get("name");
    }

    @Nullable
    private static Track track(@Nullable Object object) {
        if (!StableCaptionBlocks.isEnabled()
                || !(object instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track)) {
            return null;
        }
        try {
            String key = sourceIdentity(track);
            if (key == null) return null;

            var parameters = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            final boolean translated = parameters.containsKey("tlang");
            String secondary = secondaryLanguage();
            // A single language keeps the text and clock of the track. A translated response
            // must never become the source of a translation to a second language.
            if (secondary != null && translated) return null;

            return new Track(
                    key,
                    AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode()),
                    secondary,
                    parameters.get("v"),
                    "asr".equals(parameters.get("kind")),
                    translated);
        } catch (Exception ex) {
            Logger.printException(() -> "track failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     */
    public static synchronized void onVideoId(@Nullable String id) {
        if (!StableCaptionBlocks.isEnabled() || (id != null && id.equals(video))) return;

        PreferredCaptionLanguagePatch.forgetSelectionForVideo(id);
        video = id;
        source = null;
        selectedInputLanguage = null;
        selectionDisabled = false;
        clearRequestedOutput();
        invalidateTranslation();
    }

    /**
     * Injection point.
     * Only an actual selection of the player can change the source, never a late response.
     */
    public static synchronized void onSelectedTrack(@Nullable Object object) {
        if (!StableCaptionBlocks.isEnabled()) return;

        Track track = track(object);
        if (track == null) {
            if (!dualCaptionEnabled()) {
                clearRequestedOutput();
            } else if (translatedSession != null && source != null) {
                requestedSource = source;
                requestedTarget = translatedSession.primaryLanguage;
            }
            selectionDisabled = true;
            selectedInputLanguage = null;
            invalidateTranslation();
            return;
        }

        onVideoId(track.video);
        selectionDisabled = false;
        selectedInputLanguage = track.language;
        if (!track.source.equals(requestedSource)) clearRequestedOutput();
        if (translatedKey != null && !translatedKey.startsWith(track.source + "|" + track.language + "|")) {
            invalidateTranslation();
        }
        source = track.source;
    }

    /**
     * Sets the language to show as primary language of the given original track.
     *
     * @return If the track is a valid source.
     */
    static synchronized boolean requestOutput(AutoTranslateCaptionsPatch.CaptionTrackInterface original,
                                              String target) {
        Track selected = track(original);
        if (selected == null) return false;

        onVideoId(selected.video);
        requestedSource = selected.source;
        requestedTarget = AutoTranslateCaptionsPatch.getLanguageKey(target);

        List<StableCaptionBlocks.State> cached = new ArrayList<>();
        for (WeakReference<StableCaptionBlocks.State> reference : translatedStates) {
            StableCaptionBlocks.State state = reference.get();
            if (state != null && state.captured != null
                    && !state.captured.track.translated
                    && state.captured.track.source.equals(requestedSource)
                    && state.captured.track.language.equals(selected.language)) {
                cached.add(state);
            }
        }
        invalidateTranslation();
        for (StableCaptionBlocks.State state : cached) {
            attachTranslation(selected, state.captured.policy, state);
        }
        translationChanged(translatedToken);
        return true;
    }

    /**
     * @return The track to select to keep the primary language. The native player remembers the
     *         source track, while bilingual captions can use another language as primary language.
     */
    static synchronized PreferredCaptionLanguagePatch.CaptionTrackInterface primaryTrack(
            PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
        try {
            if (track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && requestedSource != null && requestedSource.equals(sourceIdentity(original))) {
                var translated = AutoTranslateCaptionsPatch.createTranslation(
                        original, requestedTarget, Requester.getAppLocale());
                if (translated != null) return translated;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "primaryTrack failure", ex);
        }
        return track;
    }

    static synchronized void clearRequestedOutput() {
        requestedSource = null;
        requestedTarget = null;
    }

    /**
     * Called before a caption track is parsed.
     */
    static void setTrack(@Nullable Object object) {
        parsing.remove();
        Track track = track(object);
        if (track != null) parsing.set(track);
    }

    static boolean observing() {
        return parsing.get() != null;
    }

    /**
     * Injection point.
     * Result of a track request. Can be a cached native timeline.
     */
    public static synchronized void onNativeResult(@Nullable Object request, @Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return;

        try {
            if (!(request instanceof TranslatedCaptionPensPatch.CaptionRequestInterface captionRequest)
                    || !(result instanceof StableCaptionBlocks.Timeline timeline)) return;

            Track track = track(captionRequest.patch_getCaptionTrack());
            StableCaptionBlocks.State state = timeline.patch_getBlockState();
            Captured captured = state == null ? null : state.captured;
            if (track == null
                    || captured == null
                    || !isCurrent(track)
                    || !track.source.equals(captured.track.source)
                    || !track.language.equals(captured.track.language)) return;

            attachTranslation(track, captured.policy, state);
        } catch (Exception ex) {
            Logger.printException(() -> "onNativeResult failure", ex);
        }
    }

    private static boolean isCurrent(Track track) {
        return !selectionDisabled
                && (video == null || track.video.equals(video))
                && (source == null || track.source.equals(source))
                && (selectedInputLanguage == null || selectedInputLanguage.equals(track.language));
    }

    /**
     * Called with the document of the track given to {@link #setTrack(Object)}.
     */
    static synchronized void observe(@Nullable Document document, @Nullable StableCaptionBlocks.State state) {
        Track track = parsing.get();
        parsing.remove();
        if (track == null || !isCurrent(track)) return;

        StableCaptionBlockPolicy primary;
        if (state != null && state.policy != null) {
            primary = state.policy;
        } else {
            primary = track.automatic
                    ? StableCaptionBlockPolicy.parseSource(document)
                    : StableCaptionBlockPolicy.parseManual(document);
        }
        if (primary == null) {
            invalidateTranslation();
            return;
        }

        if (state != null) state.captured = new Captured(track, primary);
        attachTranslation(track, primary, state);
    }

    /**
     * Toggling captions can reuse a cached timeline without another result callback.
     *
     * @return If the timeline of the state has an active session.
     */
    static synchronized boolean restoreTranslation(StableCaptionBlocks.State state) {
        Captured captured = state.captured;
        if (captured == null || !isCurrent(captured.track)) return false;

        String secondary = secondaryLanguage();
        if (secondary != null && captured.track.translated) return false;

        attachTranslation(captured.track.withSecondary(secondary), captured.policy, state);
        return state.translation != null && state.translation.isActive();
    }

    private static void invalidateTranslation() {
        CaptionSession session = translatedSession;
        translatedSession = null;
        translatedPolicy = null;
        translatedKey = null;
        translatedToken = null;
        translatedStates.clear();

        if (session != null) {
            session.invalidate();
            Utils.runOnBackgroundThread(CaptionBlockTranslator.DEFAULT::cancelInactive);
        }
    }

    private static void attachTranslation(Track track, StableCaptionBlockPolicy policy,
                                          @Nullable StableCaptionBlocks.State state) {
        if (state == null) return;

        String primary = track.source.equals(requestedSource) ? requestedTarget : track.language;
        if (track.secondary != null && !track.source.equals(requestedSource)) {
            primary = PreferredCaptionLanguagePatch.blockTranslationTarget(
                    track.language,
                    Settings.PREFERRED_CAPTION_LANGUAGE.get(),
                    PreferredCaptionLanguagePatch.hasUserSelectedTrack(),
                    Requester.getAppLocale());
        }

        String key = track.source + "|" + track.language + "|" + primary + "|" + track.secondary;
        if (translatedSession == null || !translatedSession.isActive()
                || translatedPolicy != policy || !key.equals(translatedKey)) {
            invalidateTranslation();

            Object token = new Object();
            translatedToken = token;
            translatedKey = key;
            translatedPolicy = policy;
            translatedSession = new CaptionSession(
                    policy.sourceBlocks(track.language),
                    track.language,
                    primary,
                    track.secondary,
                    CaptionBlockTranslator.DEFAULT,
                    Utils::runOnBackgroundThread,
                    () -> translationChanged(token),
                    () -> translationFailed(token));
        }

        state.translation = translatedSession;
        state.policy = policy;

        translatedStates.removeIf(reference -> reference.get() == null || reference.get() == state);
        if (translatedStates.size() == MAX_BOUND_STATES) translatedStates.remove(0);
        translatedStates.add(new WeakReference<>(state));
    }

    private static void translationChanged(@Nullable Object token) {
        Utils.runOnMainThread(() -> {
            synchronized (CaptionTracks.class) {
                if (token != translatedToken || translatedSession == null) return;

                for (WeakReference<StableCaptionBlocks.State> reference : translatedStates) {
                    StableCaptionBlocks.State state = reference.get();
                    if (state != null) StableCaptionBlocks.refreshTranslation(state, translatedSession);
                }
            }
        });
    }

    private static void translationFailed(Object token) {
        Utils.runOnMainThread(() -> {
            synchronized (CaptionTracks.class) {
                if (token == translatedToken) {
                    Utils.showToastShort(StringRef.str("morphe_caption_block_translation_failed"));
                }
            }
        });
    }
}

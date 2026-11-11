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
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.VideoState;

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
    /**
     * Time to wait for the uploaded tracks of the output languages, before they are translated.
     */
    private static final long UPLOADED_TRACK_TIMEOUT_MS = 5000;

    private static final Map<StableCaptionBlocks.Timeline, Object> nativeSourceRequests = new WeakHashMap<>();
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

    /**
     * Native caption track handler. Methods are added during patching.
     */
    public interface CaptionTrackHandlerInterface {
        /**
         * Requests a track with the native loader, without selecting it.
         */
        void patch_fetchCaption(Object track, UploadedCaptionCallback callback);

        /**
         * Selects the current track of the subtitle manager again.
         */
        void patch_reloadCaption();

        /**
         * Updates the installed caption renderer with the playback time.
         */
        void patch_updateCaption(long time, float speed);
    }

    record UploadedCandidate(Object track, String language, String video) {}

    private record UploadedLoad(UploadedCandidate candidate, boolean primary) {}

    /**
     * Uploaded tracks of the output languages, requested with the next track selection.
     */
    private static List<UploadedLoad> pendingUploads = List.of();
    /**
     * If an uploaded track is used for the language, instead of a translation.
     */
    private static boolean primaryUploadPlanned, secondaryUploadPlanned;
    @Nullable
    private static UploadedCaptionPair uploaded;
    private static Object uploadedToken = new Object();
    @Nullable
    private static String uploadedKey;
    private static WeakReference<CaptionTrackHandlerInterface> uploadedHandler = new WeakReference<>(null);

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
        // A cached styled track may already be selected, including when both outputs are translations.
        if (enabled) reloadUploaded();
    }

    static synchronized boolean isCurrentVideo(@Nullable String id) {
        return id != null && id.equals(video);
    }

    static synchronized boolean isSelectedVideo(@Nullable String id) {
        return !selectionDisabled && source != null && isCurrentVideo(id);
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
        invalidateUploaded();
        nativeSourceRequests.clear();
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
            if (track == null || !isCurrent(track)) return;
            StableCaptionBlocks.State state = timeline.patch_getBlockState();
            if (state == null && dualCaptionEnabled() && !track.automatic && !track.translated
                    && !(primaryUploadPlanned && secondaryUploadPlanned)) {
                captureNativeSource(track, timeline);
                return;
            }
            Captured captured = state == null ? null : state.captured;
            if (captured == null
                    || !track.source.equals(captured.track.source)
                    || !track.language.equals(captured.track.language)) return;

            attachTranslation(track, captured.policy, state);
        } catch (Exception ex) {
            Logger.printException(() -> "onNativeResult failure", ex);
        }
    }

    /**
     * Injection point.
     * Called after the native handler installed a result. A new renderer is not updated
     * by the player while the video is paused, so this updates it once.
     */
    public static void onNativeResultReady(Object handler, @Nullable Object request, @Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return;

        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                if (!(request instanceof TranslatedCaptionPensPatch.CaptionRequestInterface captionRequest)
                        || !(result instanceof StableCaptionBlocks.Timeline timeline)) return;
                Track selected = track(captionRequest.patch_getCaptionTrack());
                StableCaptionBlocks.State state = timeline.patch_getBlockState();
                if (selected == null) return;
                synchronized (CaptionTracks.class) {
                    if (handler != uploadedHandler.get() || !isCurrent(selected)) return;
                    if (state == null || state.nativeSource && !dualCaptionEnabled()) {
                        // Turning bilingual captions off can install a cached styled primary.
                        if (dualCaptionEnabled()) return;
                    } else if (state.nativePair != null) {
                        if (!isCurrentNativePair(state.nativePair)) return;
                    } else if (state.translation == null || state.translation != translatedSession
                            || !state.translation.isActive()) return;
                }
                final long time = VideoInformation.getVideoTime();
                if (time >= 0) {
                    ((CaptionTrackHandlerInterface) handler).patch_updateCaption(
                            time, VideoInformation.getPlaybackSpeed());
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onNativeResultReady failure", ex);
            }
        });
    }

    /** Sample immutable native windows off the UI thread; cache source metadata on the parsed result. */
    private static void captureNativeSource(Track track, StableCaptionBlocks.Timeline timeline) {
        if (nativeSourceRequests.containsKey(timeline) || nativeSourceRequests.size() >= MAX_BOUND_STATES) return;
        Object token = new Object();
        nativeSourceRequests.put(timeline, token);
        Utils.runOnBackgroundThread(() -> {
            StableCaptionBlockPolicy captured = null;
            try {
                captured = StableCaptionBlockPolicy.fromNative(timeline, () -> {
                    synchronized (CaptionTracks.class) {
                        return nativeSourceRequests.get(timeline) == token && isCurrent(track);
                    }
                });
            } catch (Exception ex) {
                Logger.printException(() -> "captureNativeSource failure", ex);
            }
            StableCaptionBlockPolicy policy = captured;
            Utils.runOnMainThread(() -> {
                synchronized (CaptionTracks.class) {
                    if (nativeSourceRequests.get(timeline) != token) return;
                    nativeSourceRequests.remove(timeline);
                    if (policy == null || !isCurrent(track)) return;
                    StableCaptionBlocks.State state = new StableCaptionBlocks.State(false);
                    state.nativeSource = true;
                    state.policy = policy;
                    state.captured = new Captured(track, policy);
                    timeline.patch_setBlockState(state);
                    if (dualCaptionEnabled()) attachTranslation(track.withSecondary(secondaryLanguage()), policy, state);
                }
                if (dualCaptionEnabled()) reloadUploaded();
            });
        });
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
        if (track == null) return;
        boolean current = isCurrent(track);

        StableCaptionBlockPolicy primary;
        if (state != null && state.policy != null) {
            primary = state.policy;
        } else {
            primary = track.automatic
                    ? StableCaptionBlockPolicy.parseSource(document)
                    : StableCaptionBlockPolicy.parseManual(document);
        }
        if (primary == null) {
            if (current) invalidateTranslation();
            return;
        }

        // A native prefetch may later be selected from the native parsed-result cache.
        if (state != null) {
            state.captured = new Captured(track, primary);
            state.policy = primary;
        }
        if (current) attachTranslation(track, primary, state);
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

        boolean firstUploaded = primaryUploadPlanned && uploadedEnabled();
        boolean secondUploaded = secondaryUploadPlanned && uploadedEnabled();
        String key = track.source + "|" + track.language + "|" + primary + "|" + track.secondary
                + "|" + firstUploaded + "|" + secondUploaded;
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
                    () -> translationFailed(token), !firstUploaded, !secondUploaded);
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

    /**
     * Plans requests of the uploaded tracks of the output languages, so bilingual captions
     * can show uploaded text instead of a machine translation.
     * Called when the subtitle manager selects a track.
     */
    static synchronized void inspectUploadedTracks(
            PreferredCaptionLanguagePatch.SubtitleManagerInterface manager,
            @Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface selected) {
        if (!Settings.DUAL_CAPTIONS_ENABLED.get()) return;
        String secondary = Settings.SECONDARY_CAPTION_LANGUAGE.get();
        if ("off".equalsIgnoreCase(secondary)) {
            invalidateUploaded();
            return;
        }
        String primary = Settings.PREFERRED_CAPTION_LANGUAGE.get();
        boolean appLanguage = "default".equalsIgnoreCase(primary);
        if (appLanguage) primary = Requester.getAppLocale().toLanguageTag();
        if ("off".equalsIgnoreCase(primary) || PreferredCaptionLanguagePatch.hasUserSelectedTrack()) {
            primary = selected == null ? "" : selected.patch_getLanguageCode();
            appLanguage = false;
        }
        List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog = manager.patch_getAllCaptionTracks();
        if (catalog == null) return;
        UploadedCandidate first = findUploadedTrack(catalog, primary, appLanguage);
        UploadedCandidate second = first != null
                && first.language.equals(AutoTranslateCaptionsPatch.normalizeLanguageCode(secondary))
                ? null : findUploadedTrack(catalog, secondary);
        if ((first == null && second == null)
                || (first != null && second != null && !first.video.equals(second.video))) {
            invalidateUploaded();
            return;
        }
        onVideoId(first != null ? first.video : second.video);
        String key = primary + "|" + secondary;
        if (key.equals(uploadedKey)) return;

        uploadedKey = key;
        uploadedToken = new Object();
        uploaded = null;
        primaryUploadPlanned = first != null;
        secondaryUploadPlanned = second != null;
        List<UploadedLoad> loads = new ArrayList<>();
        if (first != null) loads.add(new UploadedLoad(first, true));
        if (second != null) loads.add(new UploadedLoad(second, false));
        pendingUploads = List.copyOf(loads);
        Logger.printDebug(() -> "Uploaded caption tracks: primary=" + (first == null ? null : first.language)
                + " secondary=" + (second == null ? null : second.language));

        // A track that does not load in time is translated instead.
        Object token = uploadedToken;
        Utils.runOnMainThreadDelayed(() -> {
            final boolean missing;
            synchronized (CaptionTracks.class) {
                if (token != uploadedToken) return;
                missing = primaryUploadPlanned && (uploaded == null || uploaded.first() == null)
                        || secondaryUploadPlanned && (uploaded == null || uploaded.second() == null);
                if (uploaded == null || uploaded.first() == null) primaryUploadPlanned = false;
                if (uploaded == null || uploaded.second() == null) secondaryUploadPlanned = false;
            }
            if (missing) reloadUploaded();
        }, UPLOADED_TRACK_TIMEOUT_MS);
    }

    /**
     * @param appLanguage If the language is the app language, which can also use
     *                    an uploaded track of the language without a region.
     */
    @Nullable
    static UploadedCandidate findUploadedTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog, String language,
            boolean appLanguage) {
        UploadedCandidate exact = findUploadedTrack(catalog, language);
        if (exact != null || !appLanguage) return exact;
        String fallback = AutoTranslateCaptionsPatch.getLanguageKey(language);
        String script = Locale.forLanguageTag(language).getScript();
        if (!script.isEmpty() && !script.equals(Locale.forLanguageTag(fallback).getScript())) return null;
        return findUploadedTrack(catalog, fallback);
    }

    /**
     * @return The uploaded (not auto-generated and not translated) track of the language.
     */
    @Nullable
    static UploadedCandidate findUploadedTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog, String language) {
        String target = AutoTranslateCaptionsPatch.normalizeLanguageCode(language);
        for (var item : catalog) {
            if (!(item instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track)
                    || !target.equals(AutoTranslateCaptionsPatch.normalizeLanguageCode(track.patch_getLanguageCode()))
                    || sourceIdentity(track) == null || !track.patch_getVssId().startsWith(".")) continue;
            var fields = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            if (fields == null || fields.containsKey("tlang") || fields.containsKey("kind")) continue;
            return new UploadedCandidate(track, target, fields.get("v"));
        }
        return null;
    }

    /**
     * Injection point.
     * Called when the native caption track handler selects a track,
     * and requests the planned uploaded tracks with the same handler.
     */
    public static void fetchUploadedTracks(Object handler, @Nullable Object selected) {
        if (!StableCaptionBlocks.isEnabled()) return;

        try {
            if (!(selected instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface selectedTrack)) return;
            CaptionTrackHandlerInterface trackHandler = (CaptionTrackHandlerInterface) handler;
            var fields = AutoTranslateCaptionsPatch.captionParameters(selectedTrack.patch_getUrl());
            List<UploadedLoad> loads;
            Object token;
            synchronized (CaptionTracks.class) {
                if (fields == null || !isCurrentVideo(fields.get("v"))) return;
                uploadedHandler = new WeakReference<>(trackHandler);
                if (pendingUploads.isEmpty()) return;
                loads = pendingUploads;
                pendingUploads = List.of();
                token = uploadedToken;
            }
            for (UploadedLoad load : loads) {
                trackHandler.patch_fetchCaption(load.candidate.track,
                        new UploadedCaptionCallback(token, load.primary));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fetchUploadedTracks failure", ex);
        }
    }

    private static void invalidateUploaded() {
        uploadedToken = new Object();
        uploadedKey = null;
        pendingUploads = List.of();
        uploaded = null;
        primaryUploadPlanned = secondaryUploadPlanned = false;
        uploadedHandler.clear();
    }

    /**
     * Selects the current track again, so the native result is merged with the uploaded tracks.
     */
    private static void reloadUploaded() {
        try {
            CaptionTrackHandlerInterface handler;
            synchronized (CaptionTracks.class) {
                handler = uploadedHandler.get();
            }
            if (handler != null) handler.patch_reloadCaption();
            else PreferredCaptionLanguagePatch.refreshCaptionSelection();
        } catch (Exception ex) {
            Logger.printException(() -> "reloadUploaded failure", ex);
        }
    }

    private static boolean uploadedEnabled() {
        return dualCaptionEnabled() && isSelectedVideo(video);
    }

    static synchronized boolean hasUploadedTracks() {
        return uploaded != null && uploadedEnabled();
    }

    /**
     * Injection point.
     * Adds the windows of the uploaded tracks to a result the native renderer installs,
     * so the renderer keeps the timing of all languages. Cached results are not changed.
     */
    @Nullable
    public static synchronized Object mergeUploadedTracks(@Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return result;

        try {
            if (!(result instanceof StableCaptionBlocks.Timeline timeline) || !hasUploadedTracks()) return result;
            if (nativeWindows(uploaded.first()) || nativeWindows(uploaded.second())) {
                // Keep uploaded windows native; a missing output uses a separate translated window.
                // Cached windows and event lists remain unchanged.
                StableCaptionBlocks.Timeline sourceTimeline = timeline;
                boolean fallback = uploaded.first() == null || uploaded.second() == null;
                StableCaptionBlocks.State sourceState = sourceTimeline.patch_getBlockState();
                if (fallback) {
                    if (uploaded.first() != null) timeline = timeline.patch_mergeWith(uploaded.first());
                    if (uploaded.second() != null) timeline = timeline.patch_mergeWith(uploaded.second());
                } else timeline = uploaded.first().patch_mergeWith(uploaded.second());
                StableCaptionBlocks.State state = new StableCaptionBlocks.State(false);
                state.nativePair = new NativeCaptionPair(uploaded, fallback ? sourceTimeline : null);
                if (fallback && sourceState != null && sourceState.captured != null) {
                    Captured captured = sourceState.captured;
                    state.captured = captured;
                    attachTranslation(captured.track.withSecondary(secondaryLanguage()), captured.policy, state);
                }
                timeline.patch_setBlockState(state);
                return timeline;
            }
            if (uploaded.first() != null) timeline = timeline.patch_mergeWith(uploaded.first());
            if (uploaded.second() != null) timeline = timeline.patch_mergeWith(uploaded.second());
            return timeline;
        } catch (Exception ex) {
            Logger.printException(() -> "mergeUploadedTracks failure", ex);
            return result;
        }
    }

    private static boolean nativeWindows(@Nullable StableCaptionBlocks.Timeline timeline) {
        if (timeline == null) return false;
        StableCaptionBlocks.State state = timeline.patch_getBlockState();
        return state == null || state.nativeSource;
    }

    static synchronized boolean isCurrentNativePair(NativeCaptionPair pair) {
        return uploadedEnabled() && uploaded == pair.tracks;
    }

    /**
     * @param fallback Session that translates the languages without an uploaded track.
     * @return Text of the uploaded tracks at the time, or null if there are no uploaded tracks.
     */
    @Nullable
    static synchronized UploadedCaptionPair.Frame uploadedFrame(long time, @Nullable CaptionSession fallback) {
        UploadedCaptionPair current = uploaded;
        if (current == null || !uploadedEnabled()) return null;
        CaptionSession.Snapshot translated = fallback == null ? null
                : fallback.snapshot(time, VideoState.getCurrent() == VideoState.PAUSED);
        return current.at(time, translated == null ? "" : translated.primary,
                translated == null ? "" : translated.secondary, translated == null ? -1 : translated.start);
    }

    private static void publishUploaded(UploadedCaptionCallback callback,
                                        @Nullable StableCaptionBlocks.Timeline timeline) {
        synchronized (CaptionTracks.class) {
            if (callback.token != uploadedToken) return;
            StableCaptionBlocks.Timeline first = uploaded == null ? null : uploaded.first();
            StableCaptionBlocks.Timeline second = uploaded == null ? null : uploaded.second();
            if (callback.primary) {
                first = timeline;
                primaryUploadPlanned = timeline != null;
            } else {
                second = timeline;
                secondaryUploadPlanned = timeline != null;
            }
            uploaded = first == null && second == null ? null : new UploadedCaptionPair(first, second);
        }
        Utils.runOnMainThread(() -> {
            synchronized (CaptionTracks.class) {
                if (callback.token != uploadedToken) return;
            }
            reloadUploaded();
        });
    }

    /**
     * Result callback of an uploaded track request.
     * During patching this class implements the request callback interface of the native loader.
     */
    public static final class UploadedCaptionCallback {
        private final Object token;
        private final boolean primary;

        UploadedCaptionCallback(Object token, boolean primary) {
            this.token = token;
            this.primary = primary;
        }

        /**
         * Called by the native loader.
         */
        public void onResult(@Nullable Object result) {
            try {
                // A track without any cues has at most one event time.
                publishUploaded(this, result instanceof StableCaptionBlocks.Timeline timeline
                        && timeline.patch_getCaptionEventTimes().size() > 1 ? timeline : null);
            } catch (Exception ex) {
                Logger.printException(() -> "onResult failure", ex);
            }
        }

        /**
         * Called by the native loader.
         */
        public void onError(@Nullable Exception error) {
            try {
                Logger.printDebug(() -> "Uploaded caption track request failed", error);
                publishUploaded(this, null);
            } catch (Exception ex) {
                Logger.printException(() -> "onError failure", ex);
            }
        }
    }
}

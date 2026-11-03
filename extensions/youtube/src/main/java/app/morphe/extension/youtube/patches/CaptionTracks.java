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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final long ORIGINAL_TRACK_TIMEOUT_MS = 5000;

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

    public interface CaptionLoader {
        void patch_fetchCaption(Object track, Object ticket);

        void patch_reloadCaption();

        void patch_updateCaption(long time, float speed);
    }

    record OriginalCandidate(Object track, String language, String video) {}

    private record OriginalLoad(OriginalCandidate candidate, boolean primary) {}

    private static List<OriginalLoad> pendingOriginals = List.of();
    private static boolean primaryOriginalPlanned, secondaryOriginalPlanned;
    @Nullable
    private static AuthoredCaptionPair originals;
    private static Object originalsToken = new Object();
    @Nullable
    private static String originalsKey;
    private static WeakReference<CaptionLoader> originalsLoader = new WeakReference<>(null);

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
        invalidateOriginals();
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

    /** A newly installed native renderer receives no player tick while the video is paused. */
    public static void onNativeResultReady(Object receiver, @Nullable Object request, @Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return;

        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                if (!(receiver instanceof CaptionLoader loader)
                        || !(request instanceof TranslatedCaptionPensPatch.CaptionRequestInterface captionRequest)
                        || !(result instanceof StableCaptionBlocks.Timeline timeline)) return;
                Track selected = track(captionRequest.patch_getCaptionTrack());
                StableCaptionBlocks.State state = timeline.patch_getBlockState();
                if (selected == null || state == null) return;
                synchronized (CaptionTracks.class) {
                    if (loader != originalsLoader.get() || !isCurrent(selected)
                            || state.translation == null || state.translation != translatedSession
                            || !state.translation.isActive()) return;
                }
                final long time = VideoInformation.getVideoTime();
                if (time >= 0) loader.patch_updateCaption(time, VideoInformation.getPlaybackSpeed());
            } catch (Exception ex) {
                Logger.printException(() -> "onNativeResultReady failure", ex);
            }
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

        boolean firstOriginal = primaryOriginalPlanned && originalsEnabled();
        boolean secondOriginal = secondaryOriginalPlanned && originalsEnabled();
        String key = track.source + "|" + track.language + "|" + primary + "|" + track.secondary
                + "|" + firstOriginal + "|" + secondOriginal;
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
                    () -> translationFailed(token), !firstOriginal, !secondOriginal);
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

    static synchronized void inspectOriginals(
            PreferredCaptionLanguagePatch.SubtitleManagerInterface manager,
            @Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface selected) {
        if (!Settings.DUAL_CAPTIONS_ENABLED.get()) return;
        String secondary = Settings.SECONDARY_CAPTION_LANGUAGE.get();
        if ("off".equalsIgnoreCase(secondary)) {
            invalidateOriginals();
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
        OriginalCandidate first = findOriginalTrack(catalog, primary, appLanguage);
        OriginalCandidate second = first != null
                && first.language.equals(AutoTranslateCaptionsPatch.normalizeLanguageCode(secondary))
                ? null : findOriginalTrack(catalog, secondary);
        if (first == null && second == null) {
            invalidateOriginals();
            return;
        }
        if (first != null && second != null && !first.video.equals(second.video)) {
            invalidateOriginals();
            return;
        }
        String id = first != null ? first.video : second.video;
        onVideoId(id);
        String key = primary + "|" + secondary;
        if (key.equals(originalsKey)) return;
        originalsKey = key;
        originalsToken = new Object();
        originals = null;
        primaryOriginalPlanned = first != null;
        secondaryOriginalPlanned = second != null;
        List<OriginalLoad> loads = new ArrayList<>();
        if (first != null) loads.add(new OriginalLoad(first, true));
        if (second != null) loads.add(new OriginalLoad(second, false));
        pendingOriginals = List.copyOf(loads);
        Object token = originalsToken;
        Utils.runOnMainThreadDelayed(() -> {
            final boolean missing;
            synchronized (CaptionTracks.class) {
                if (token != originalsToken) return;
                missing = primaryOriginalPlanned && (originals == null || originals.first() == null)
                        || secondaryOriginalPlanned && (originals == null || originals.second() == null);
                if (originals == null || originals.first() == null) primaryOriginalPlanned = false;
                if (originals == null || originals.second() == null) secondaryOriginalPlanned = false;
            }
            if (missing) refreshOriginals();
        }, ORIGINAL_TRACK_TIMEOUT_MS);
        final int count = catalog.size();
        Logger.printInfo(() -> "Original captions catalog=" + count + " authored originals resolved");
    }

    @Nullable
    static OriginalCandidate findOriginalTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog, String language,
            boolean appLanguage) {
        OriginalCandidate exact = findOriginalTrack(catalog, language);
        if (exact != null || !appLanguage) return exact;
        String fallback = AutoTranslateCaptionsPatch.getLanguageKey(language);
        String script = Locale.forLanguageTag(language).getScript();
        if (!script.isEmpty() && !script.equals(Locale.forLanguageTag(fallback).getScript())) return null;
        return findOriginalTrack(catalog, fallback);
    }

    @Nullable
    static OriginalCandidate findOriginalTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog, String language) {
        String target = AutoTranslateCaptionsPatch.normalizeLanguageCode(language);
        for (var item : catalog) {
            if (!(item instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track)
                    || !target.equals(AutoTranslateCaptionsPatch.normalizeLanguageCode(track.patch_getLanguageCode()))
                    || sourceIdentity(track) == null || !track.patch_getVssId().startsWith(".")) continue;
            var fields = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            if (fields == null || fields.containsKey("tlang") || fields.containsKey("kind")) continue;
            return new OriginalCandidate(track, target, fields.get("v"));
        }
        return null;
    }

    public static void onCaptionHandler(Object receiver, @Nullable Object selected) {
        if (!StableCaptionBlocks.isEnabled()) return;
        try {
            if (!(receiver instanceof CaptionLoader handler)
                    || !(selected instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface selectedTrack)) return;
            var fields = AutoTranslateCaptionsPatch.captionParameters(selectedTrack.patch_getUrl());
            List<OriginalLoad> work;
            Object token;
            synchronized (CaptionTracks.class) {
                if (fields == null || !isCurrentVideo(fields.get("v"))) return;
                originalsLoader = new WeakReference<>(handler);
                if (pendingOriginals.isEmpty()) return;
                work = pendingOriginals;
                pendingOriginals = List.of();
                token = originalsToken;
            }
            for (OriginalLoad load : work) {
                OriginalCandidate candidate = load.candidate;
                try {
                    handler.patch_fetchCaption(candidate.track, new OriginalTicket(token, load.primary));
                } catch (Exception ex) {
                    Logger.printException(() -> "patch_fetchCaption failure", ex);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCaptionHandler failure", ex);
        }
    }

    private record OriginalTicket(Object originalsToken, boolean primary) {}

    private static void invalidateOriginals() {
        originalsToken = new Object();
        originalsKey = null;
        pendingOriginals = List.of();
        originals = null;
        primaryOriginalPlanned = secondaryOriginalPlanned = false;
        originalsLoader.clear();
    }

    private static void refreshOriginals() {
        try {
            CaptionLoader loader;
            synchronized (CaptionTracks.class) {
                loader = originalsLoader.get();
            }
            if (loader != null) loader.patch_reloadCaption();
            else PreferredCaptionLanguagePatch.refreshCaptionSelection();
        } catch (Exception ex) {
            Logger.printException(() -> "refreshOriginals failure", ex);
        }
    }

    private static boolean originalsEnabled() {
        return dualCaptionEnabled() && isSelectedVideo(video);
    }

    static synchronized boolean hasOriginalTracks() {
        return originals != null && originalsEnabled();
    }

    /** The native renderer owns timing; cached source timelines remain untouched. */
    @Nullable
    public static synchronized Object mergeOriginalTracks(@Nullable Object original) {
        if (!StableCaptionBlocks.isEnabled()) return original;
        try {
            if (!(original instanceof StableCaptionBlocks.Timeline timeline) || !hasOriginalTracks()) return original;
            if (originals.first() != null) timeline = timeline.patch_mergeWith(originals.first());
            if (originals.second() != null) timeline = timeline.patch_mergeWith(originals.second());
            return timeline;
        } catch (Exception ex) {
            Logger.printException(() -> "mergeOriginalTracks failure", ex);
            return original;
        }
    }

    @Nullable
    static synchronized AuthoredCaptionPair.Frame originalFrame(long time, @Nullable CaptionSession fallback) {
        AuthoredCaptionPair current = originals;
        if (current == null || !originalsEnabled()) return null;
        CaptionSession.Snapshot translated = fallback == null ? null
                : fallback.snapshot(time, VideoState.getCurrent() == VideoState.PAUSED);
        return current.at(time, translated == null ? "" : translated.primary,
                translated == null ? "" : translated.secondary, translated == null ? -1 : translated.start);
    }

    private static void publishOriginal(OriginalTicket ticket, @Nullable StableCaptionBlocks.Timeline timeline) {
        synchronized (CaptionTracks.class) {
            if (ticket.originalsToken != originalsToken) return;
            StableCaptionBlocks.Timeline first = originals == null ? null : originals.first();
            StableCaptionBlocks.Timeline second = originals == null ? null : originals.second();
            if (ticket.primary) {
                first = timeline;
                primaryOriginalPlanned = timeline != null;
            } else {
                second = timeline;
                secondaryOriginalPlanned = timeline != null;
            }
            originals = first == null && second == null ? null : new AuthoredCaptionPair(first, second);
        }
        Utils.runOnMainThread(() -> {
            synchronized (CaptionTracks.class) {
                if (ticket.originalsToken != originalsToken) return;
            }
            refreshOriginals();
        });
    }

    /** Uses the native callback interface resolved by the bytecode patch. */
    public static Object originalCallback(Class<?> type, Object ticket) {
        try {
            OriginalTicket request = (OriginalTicket) ticket;
            AtomicBoolean completed = new AtomicBoolean();
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                try {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "OriginalCaptionCallback";
                        };
                    }
                    if (args == null || args.length != 2 || !completed.compareAndSet(false, true)) return null;
                    synchronized (CaptionTracks.class) {
                        if (request.originalsToken != originalsToken) return null;
                    }
                    publishOriginal(request, args[1] instanceof StableCaptionBlocks.Timeline timeline
                            && timeline.patch_getCaptionEventTimes().size() > 1 ? timeline : null);
                } catch (Exception ex) {
                    Logger.printException(() -> "originalCallback failure", ex);
                    publishOriginal(request, null);
                }
                return null;
            });
        } catch (Exception ex) {
            Logger.printException(() -> "originalCallback failure", ex);
            throw new IllegalStateException("Could not create native caption callback", ex);
        }
    }
}

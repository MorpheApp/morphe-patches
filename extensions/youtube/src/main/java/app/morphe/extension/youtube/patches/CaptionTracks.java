/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.requests.Requester;

import org.w3c.dom.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

/** Captures the selected source and binds caption outputs to its lifecycle. */
public final class CaptionTracks {
    private static final ThreadLocal<Track> parsing = new ThreadLocal<>();
    private static String source, video, selectedInputLanguage;
    private static String requestedSource, requestedTarget;
    private static boolean selectionDisabled;
    private static CaptionSession translatedSession;
    private static StableCaptionBlockPolicy translatedPolicy;
    private static String translatedKey;
    private static volatile Object translatedToken;
    private static final List<WeakReference<StableCaptionBlocks.State>> translatedStates = new ArrayList<>();

    private CaptionTracks() {}

    private static final class Track {
        final String source, language, secondary, video;
        final boolean automatic, translated;

        Track(String source, String language, String secondary, String video, boolean automatic, boolean translated) {
            this.video = video;
            this.source = source;
            this.language = language;
            this.secondary = secondary;
            this.automatic = automatic;
            this.translated = translated;
        }
    }

    private static boolean isPatchIncluded() {
        return false; // Modified only after structural source lifecycle hooks resolve.
    }

    public static boolean isAvailable() { return isPatchIncluded(); }

    public static boolean dualCaptionEnabled() {
        return Settings.DUAL_CAPTIONS_ENABLED.get() && StableCaptionBlocks.blockLayoutEnabled()
                && AutoTranslateCaptionsPatch.translationLanguage(Settings.SECONDARY_CAPTION_LANGUAGE.get()) != null;
    }

    /** Reapply the actual native selection; cached originals may need no native reload. */
    public static void setDualCaptionsEnabled(boolean enabled) {
        Map<StableCaptionBlocks.State, Long> cached = new LinkedHashMap<>();
        synchronized (CaptionTracks.class) {
            Settings.DUAL_CAPTIONS_ENABLED.save(enabled);
            for (WeakReference<StableCaptionBlocks.State> ref : translatedStates) {
                StableCaptionBlocks.State state = ref.get();
                if (state != null && state.captured != null && state.translation != null)
                    cached.put(state, state.translation.playbackTime());
            }
            invalidateTranslation();
        }
        PreferredCaptionLanguagePatch.refreshCaptionSelection();
        synchronized (CaptionTracks.class) {
            for (var entry : cached.entrySet()) {
                StableCaptionBlocks.State state = entry.getKey();
                Captured captured = state.captured;
                Track previous = captured.track;
                if (!current(previous) || enabled && previous.translated) continue;
                Track next = new Track(previous.source, previous.language,
                        dualCaptionEnabled() ? Settings.SECONDARY_CAPTION_LANGUAGE.get() : null,
                        previous.video, previous.automatic, previous.translated);
                state.captured = new Captured(next, captured.policy);
                attachTranslation(next, captured.policy, state);
                state.translation.demand(entry.getValue());
            }
            translationChanged(translatedToken);
            for (StableCaptionBlocks.State state : cached.keySet()) StableCaptionBlocks.requestLayout(state);
        }
    }

    static synchronized boolean isCurrentVideo(String id) {
        return id != null && id.equals(video);
    }

    /** Validated identity shared across original and translated forms of the same source. */
    static String sourceIdentity(AutoTranslateCaptionsPatch.CaptionTrackInterface track) {
        if (track == null) return null;
        Map<String, String> fields =
                AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
        if (fields == null
                || fields.get("v") == null
                || !fields.get("v").matches("[A-Za-z0-9_-]{11}")
                || fields.get("lang") == null
                || !fields.get("lang").matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})*")) return null;
        String vss = track.patch_getVssId(), translated = fields.get("tlang");
        if (vss == null) return null;
        if (translated != null) {
            if (AutoTranslateCaptionsPatch.translationLanguage(translated) == null
                    || !translated.equals(track.patch_getLanguageCode())
                    || !vss.startsWith("t")
                    || !vss.endsWith("." + translated)) return null;
            vss = vss.substring(1, vss.length() - translated.length() - 1);
        } else if (!AutoTranslateCaptionsPatch.getLanguageKey(fields.get("lang"))
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode())))
            return null;
        if (!vss.matches("(?:a)?\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) return null;
        boolean asr = vss.startsWith("a.");
        String vssLanguage = vss.substring(asr ? 2 : 1).split("\\.", 2)[0];
        if (!AutoTranslateCaptionsPatch.getLanguageKey(vssLanguage)
                        .equals(AutoTranslateCaptionsPatch.getLanguageKey(fields.get("lang")))
                || (asr
                        ? !"asr".equals(fields.get("kind"))
                        : fields.get("kind") != null && !fields.get("kind").isEmpty())) return null;
        return fields.get("v") + "|" + vss + "|" + fields.get("lang") + "|" + fields.get("name");
    }

    private static Track track(Object object) {
        if (!StableCaptionBlocks.blockLayoutEnabled()) return null;
        String target = dualCaptionEnabled() ? Settings.SECONDARY_CAPTION_LANGUAGE.get() : null;
        if (!(object instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track))
            return null;
        try {
            String key = sourceIdentity(track);
            if (key == null) return null;
            var parameters = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            // Single output keeps YouTube's translated text and its own clock. A translated
            // response must never become the source for a second-language translation.
            if (target != null && parameters.containsKey("tlang")) return null;
            return new Track(
                    key,
                    AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode()),
                    target == null ? null : AutoTranslateCaptionsPatch.getLanguageKey(target),
                    parameters.get("v"),
                    "asr".equals(parameters.get("kind")), parameters.containsKey("tlang"));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static synchronized void onVideoId(String id) {
        if (id == null || !id.equals(video)) {
            PreferredCaptionLanguagePatch.forgetSelectionForVideo(id);
            video = id;
            source = null;
            selectedInputLanguage = null;
            selectionDisabled = false;
            clearRequestedOutput();
            invalidateTranslation();
        }
    }

    /**
     * Only actual player selection may change the accepted source, never a late passive response.
     */
    public static synchronized void onSelectedTrack(Object object) {
        Track track = track(object);
        if (track == null) {
            if (dualCaptionEnabled()) {
                if (translatedSession != null && source != null) {
                    requestedSource = source;
                    requestedTarget = translatedSession.primaryLanguage;
                }
            } else clearRequestedOutput();
            selectionDisabled = true;
            selectedInputLanguage = null;
            invalidateTranslation();
            return;
        }
        onVideoId(track.video);
        selectionDisabled = false;
        selectedInputLanguage = track.language;
        if (!track.source.equals(requestedSource)) clearRequestedOutput();
        if (translatedKey != null && !translatedKey.startsWith(track.source + "|" + track.language + "|"))
            invalidateTranslation();
        source = track.source;
    }

    static synchronized boolean requestOutput(AutoTranslateCaptionsPatch.CaptionTrackInterface original,
            String target) {
        Track selected = track(original);
        if (selected == null) return false;
        onVideoId(selected.video);
        requestedSource = selected.source;
        requestedTarget = AutoTranslateCaptionsPatch.getLanguageKey(target);
        List<StableCaptionBlocks.State> cached = new ArrayList<>();
        for (WeakReference<StableCaptionBlocks.State> ref : translatedStates) {
            StableCaptionBlocks.State state = ref.get();
            if (state != null && state.captured != null
                    && !state.captured.track.translated
                    && state.captured.track.source.equals(requestedSource)
                    && state.captured.track.language.equals(selected.language)) cached.add(state);
        }
        invalidateTranslation();
        for (StableCaptionBlocks.State state : cached) {
            attachTranslation(selected, state.captured.policy, state);
        }
        translationChanged(translatedToken);
        return true;
    }

    /** The native player remembers the source track while bilingual output uses another language. */
    static synchronized PreferredCaptionLanguagePatch.CaptionTrackInterface primaryTrack(
            PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
        try {
            if (track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && requestedSource != null && requestedSource.equals(sourceIdentity(original))) {
                var translated = AutoTranslateCaptionsPatch.createTranslation(
                        original, requestedTarget, Requester.getAppLocale());
                if (translated != null) return translated;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An unavailable native copy must preserve the current selection.
        }
        return track;
    }

    static synchronized void clearRequestedOutput() {
        requestedSource = requestedTarget = null;
    }

    public static void setTrack(Object object) {
        parsing.remove();
        Track track = track(object);
        if (track != null) parsing.set(track);
    }

    static boolean observing() {
        return parsing.get() != null;
    }

    static final class Captured {
        final Track track;
        final StableCaptionBlockPolicy policy;

        Captured(Track track, StableCaptionBlockPolicy policy) {
            this.track = track;
            this.policy = policy;
        }
    }

    public static synchronized void onNativeResult(Object request, Object result) {
        try {
            if (!(request instanceof TranslatedCaptionPensPatch.CaptionRequestInterface r)
                    || !(result instanceof StableCaptionBlocks.Timeline timeline)) return;
            Track track = track(r.patch_getCaptionTrack());
            StableCaptionBlocks.State state = timeline.patch_getBlockState();
            Captured captured = state == null ? null : state.captured;
            if (track == null
                    || captured == null
                    || !current(track)
                    || !track.source.equals(captured.track.source)
                    || !track.language.equals(captured.track.language)) return;
            attachTranslation(track, captured.policy, state);
        } catch (RuntimeException | LinkageError ignored) {
            // An unsupported native result must not interrupt the selected track.
        }
    }

    private static boolean current(Track track) {
        return !selectionDisabled && (video == null || track.video.equals(video))
                && (source == null || track.source.equals(source))
                && (selectedInputLanguage == null || selectedInputLanguage.equals(track.language));
    }

    static synchronized boolean observe(Document document, StableCaptionBlocks.State state) {
        Track track = parsing.get();
        parsing.remove();
        if (track == null || !current(track)) return false;
        StableCaptionBlockPolicy primary =
                state != null && state.policy != null
                        ? state.policy
                        : track.automatic
                                ? StableCaptionBlockPolicy.parseSource(document)
                                : StableCaptionBlockPolicy.parseManual(document);
        if (primary == null) {
            if (StableCaptionBlocks.blockLayoutEnabled()) invalidateTranslation();
            return false;
        }
        if (state != null) state.captured = new Captured(track, primary);
        attachTranslation(track, primary, state);
        return true;
    }

    /** CC may reuse a cached native timeline without delivering another result callback. */
    static synchronized boolean restoreTranslation(StableCaptionBlocks.State state) {
        Captured captured = state.captured;
        if (captured == null || !current(captured.track)) return false;
        Track previous = captured.track;
        String secondary = dualCaptionEnabled()
                ? AutoTranslateCaptionsPatch.getLanguageKey(Settings.SECONDARY_CAPTION_LANGUAGE.get()) : null;
        if (secondary != null && previous.translated) return false;
        Track selected = new Track(previous.source, previous.language, secondary,
                previous.video, previous.automatic, previous.translated);
        attachTranslation(selected, captured.policy, state);
        return state.translation != null && state.translation.isActive();
    }

    private static void invalidateTranslation() {
        boolean hadSession = translatedSession != null;
        if (hadSession) translatedSession.invalidate();
        translatedSession = null; translatedPolicy = null; translatedKey = null; translatedToken = null;
        translatedStates.clear();
        if (hadSession) Utils.runOnBackgroundThread(CaptionBlockTranslator.DEFAULT::cancelInactive);
    }

    private static void attachTranslation(Track track, StableCaptionBlockPolicy policy,
            StableCaptionBlocks.State state) {
        if (state == null) return;
        String primary = track.secondary == null ? track.language : PreferredCaptionLanguagePatch.blockTranslationTarget(track.language,
                Settings.PREFERRED_CAPTION_LANGUAGE.get(), PreferredCaptionLanguagePatch.hasUserSelectedTrack(),
                Requester.getAppLocale());
        if (track.secondary != null && track.source.equals(requestedSource)) primary = requestedTarget;
        String key = track.source + "|" + track.language + "|" + primary + "|" + track.secondary;
        if (translatedSession == null || !translatedSession.isActive()
                || translatedPolicy != policy || !key.equals(translatedKey)) {
            invalidateTranslation();
            Object token = new Object();
            translatedToken = token;
            translatedKey = key; translatedPolicy = policy;
            translatedSession = new CaptionSession(policy.sourceBlocks(track.language),
                    track.language, primary, track.secondary,
                    (from, to, texts) -> CaptionBlockTranslator.DEFAULT.translate(from, to, texts,
                            () -> translatedToken == token),
                    CaptionSession.worker(), () -> translationChanged(token),
                    () -> translationFailed(token));
        }
        state.translation = translatedSession;
        state.policy = policy;
        translatedStates.removeIf(ref -> ref.get() == null || ref.get() == state);
        if (translatedStates.size() == 4) translatedStates.remove(0);
        translatedStates.add(new WeakReference<>(state));
    }

    private static void translationChanged(Object token) {
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
                if (token != translatedToken) return;
                Utils.showToastShort(StringRef.str("morphe_caption_block_translation_failed"));
            }
        });
    }

    public static String[] languageCodes(String selected) {
        return AutoTranslateCaptionsPatch.translationLanguages(selected);
    }

    public static String languageName(String code, java.util.Locale locale) {
        return AutoTranslateCaptionsPatch.translationLanguageName(code, locale);
    }
}

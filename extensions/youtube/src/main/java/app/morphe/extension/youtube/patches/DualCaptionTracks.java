/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.util.Log;

import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.requests.Requester;

import org.w3c.dom.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/** Captures the selected source and binds caption outputs to its lifecycle. */
public final class DualCaptionTracks {
    private static final ThreadLocal<Track> parsing = new ThreadLocal<>();
    private static final LinkedHashMap<String, StableCaptionBlockPolicy> tracks =
            new LinkedHashMap<>();
    private static final List<Pending> pending = new ArrayList<>();
    private static String source, video, selectedInputLanguage;
    private static boolean selectionDisabled;
    private static CaptionTranslationSession translatedSession;
    private static StableCaptionBlockPolicy translatedPolicy;
    private static String translatedKey;
    private static Object translatedToken;
    private static final List<WeakReference<StableCaptionBlocks.State>> translatedStates = new ArrayList<>();

    private DualCaptionTracks() {}

    private static final class Track {
        final String source, language, secondary, video;
        final boolean automatic;

        Track(String source, String language, String secondary, String video, boolean automatic) {
            this.video = video;
            this.source = source;
            this.language = language;
            this.secondary = secondary;
            this.automatic = automatic;
        }
    }

    private static final class Pending {
        final Track track;
        final StableCaptionBlockPolicy primary;
        final WeakReference<StableCaptionBlocks.State> state;

        Pending(Track track, StableCaptionBlockPolicy primary, StableCaptionBlocks.State state) {
            this.track = track;
            this.primary = primary;
            this.state = new WeakReference<>(state);
        }
    }

    private static Track track(Object object) {
        if (!StableCaptionBlocks.isSupported()) return null;
        String target =
                AutoTranslateCaptionsPatch.translationLanguage(
                        Settings.SECONDARY_CAPTION_LANGUAGE.get());
        if ((target == null && !StableCaptionBlocks.blockTranslationEnabled())
                || !(object instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track))
            return null;
        try {
            String key = DualCaptionPrefetch.sourceIdentity(track);
            if (key == null) return null;
            var parameters = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            return new Track(
                    key,
                    AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode()),
                    target == null ? null : AutoTranslateCaptionsPatch.getLanguageKey(target),
                    parameters.get("v"),
                    "asr".equals(parameters.get("kind")));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static synchronized void onVideoId(String id) {
        if (id == null || !id.equals(video)) {
            video = id;
            source = null;
            selectedInputLanguage = null;
            selectionDisabled = false;
            tracks.clear();
            pending.clear();
            invalidateTranslation();
        }
    }

    /**
     * Only actual player selection may change the accepted source, never a late passive response.
     */
    public static synchronized void onSelectedTrack(Object object) {
        Track track = track(object);
        if (track == null) { selectionDisabled = true; selectedInputLanguage = null; invalidateTranslation(); return; }
        onVideoId(track.video);
        selectionDisabled = false;
        selectedInputLanguage = track.language;
        if (translatedKey != null && !translatedKey.startsWith(track.source + "|" + track.language + "|"))
            invalidateTranslation();
        if (!track.source.equals(source)) {
            source = track.source;
            tracks.clear();
            pending.clear();
        }
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
                    || !track.language.equals(captured.track.language)
                    || (!StableCaptionBlocks.blockTranslationEnabled()
                            && !java.util.Objects.equals(track.secondary, captured.track.secondary))) return;
            if (StableCaptionBlocks.blockTranslationEnabled()) attachTranslation(track, captured.policy, state);
            else record(track, captured.policy, state, true);
        } catch (RuntimeException | LinkageError ignored) {
            // An unsupported native result must not interrupt the selected track.
        }
    }

    private static boolean current(Track track) {
        return !selectionDisabled && (video == null || track.video.equals(video))
                && (source == null || track.source.equals(source))
                && (!StableCaptionBlocks.blockTranslationEnabled() || selectedInputLanguage == null
                        || selectedInputLanguage.equals(track.language));
    }

    static synchronized boolean observe(Document document, StableCaptionBlocks.State state) {
        Track track = parsing.get();
        parsing.remove();
        if (track == null || !current(track)) return false;
        StableCaptionBlockPolicy primary =
                state != null && state.fallbackPolicy != null
                        ? state.fallbackPolicy
                        : track.automatic
                                ? StableCaptionBlocks.blockTranslationEnabled()
                                        ? StableCaptionBlockPolicy.parseSource(document)
                                        : StableCaptionBlockPolicy.parse(document)
                                : StableCaptionBlockPolicy.parseManual(document);
        if (primary == null) {
            if (StableCaptionBlocks.blockTranslationEnabled()) invalidateTranslation();
            return false;
        }
        if (state != null) state.captured = new Captured(track, primary);
        if (StableCaptionBlocks.blockTranslationEnabled()) attachTranslation(track, primary, state);
        else record(track, primary, state, false);
        return true;
    }

    private static void record(
            Track track,
            StableCaptionBlockPolicy primary,
            StableCaptionBlocks.State state,
            boolean cached) {
        if (!track.source.equals(source)) {
            tracks.clear();
            pending.clear();
            source = track.source;
        }
        tracks.put(track.language, primary);
        if (tracks.size() > 2) {
            Iterator<String> keys = tracks.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.equals(track.secondary) && !key.equals(track.language)) {
                    keys.remove();
                    break;
                }
            }
        }
        if (state != null) {
            boolean dual = !track.language.equals(track.secondary);
            StableCaptionBlockPolicy secondary = dual ? tracks.get(track.secondary) : null;
            boolean waiting = dual && secondary == null;
            StableCaptionBlockPolicy policy =
                    secondary == null ? primary : primary.withTranslation(secondary);
            if (cached) StableCaptionBlocks.updateState(state, policy, waiting);
            else {
                state.policy = policy;
                state.dualPending = waiting;
            }
            pending.removeIf(p -> p.state.get() == null || p.state.get() == state);
            if (waiting) {
                if (pending.size() == 4) pending.remove(0);
                pending.add(new Pending(track, primary, state));
            }
        }
        Iterator<Pending> waiting = pending.iterator();
        while (waiting.hasNext()) {
            Pending p = waiting.next();
            StableCaptionBlocks.State existing = p.state.get();
            StableCaptionBlockPolicy secondary = tracks.get(p.track.secondary);
            if (existing == null) waiting.remove();
            else if (secondary != null) {
                StableCaptionBlocks.completePair(existing, p.primary.withTranslation(secondary));
                waiting.remove();
            }
        }
        Log.i(
                "MorpheDualCaptions",
                "Native track="
                        + track.language
                        + " cached="
                        + tracks.size()
                        + " paired="
                        + (tracks.get(track.secondary) != null));
    }

    private static void invalidateTranslation() {
        if (translatedSession != null) translatedSession.invalidate();
        translatedSession = null; translatedPolicy = null; translatedKey = null; translatedToken = null;
        translatedStates.clear();
    }

    private static void attachTranslation(Track track, StableCaptionBlockPolicy policy,
            StableCaptionBlocks.State state) {
        if (state == null) return;
        String requested = Settings.PREFERRED_CAPTION_LANGUAGE.get();
        if ("default".equalsIgnoreCase(requested)) requested = Requester.getAppLocale().toLanguageTag();
        String primary = "off".equalsIgnoreCase(requested) ? track.language
                : AutoTranslateCaptionsPatch.translationLanguage(requested);
        if (primary == null) primary = AutoTranslateCaptionsPatch.translationLanguage(
                java.util.Locale.forLanguageTag(requested).getLanguage());
        if (primary == null) primary = track.language;
        primary = AutoTranslateCaptionsPatch.getLanguageKey(primary);
        String key = track.source + "|" + track.language + "|" + primary + "|" + track.secondary;
        if (translatedSession == null || !translatedSession.isActive()
                || translatedPolicy != policy || !key.equals(translatedKey)) {
            invalidateTranslation();
            Object token = new Object();
            translatedToken = token;
            translatedKey = key; translatedPolicy = policy;
            translatedSession = new CaptionTranslationSession(policy.sourceBlocks(track.language),
                    track.language, primary, track.secondary, CaptionBlockTranslator.DEFAULT::translate,
                    CaptionTranslationSession.worker(), () -> translationChanged(token),
                    () -> translationFailed(token));
        }
        state.translation = translatedSession;
        state.policy = policy;
        state.dualPending = false;
        translatedStates.removeIf(ref -> ref.get() == null || ref.get() == state);
        if (translatedStates.size() == 4) translatedStates.remove(0);
        translatedStates.add(new WeakReference<>(state));
    }

    private static void translationChanged(Object token) {
        Utils.runOnMainThread(() -> {
            synchronized (DualCaptionTracks.class) {
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
            synchronized (DualCaptionTracks.class) {
                if (token != translatedToken) return;
                Utils.showToastShort(StringRef.str("morphe_caption_block_translation_failed"));
            }
        });
    }

    public static String[] languageCodes() {
        return AutoTranslateCaptionsPatch.translationLanguages();
    }
}

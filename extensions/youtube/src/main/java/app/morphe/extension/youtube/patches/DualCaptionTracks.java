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
import java.util.List;

/** Captures the selected source and binds caption outputs to its lifecycle. */
public final class DualCaptionTracks {
    private static final ThreadLocal<Track> parsing = new ThreadLocal<>();
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

    private static boolean isPatchIncluded() {
        return false; // Modified only after structural source lifecycle hooks resolve.
    }

    public static boolean isAvailable() { return isPatchIncluded(); }

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
        if (!StableCaptionBlocks.blockTranslationEnabled()) return null;
        String target =
                AutoTranslateCaptionsPatch.translationLanguage(
                        Settings.SECONDARY_CAPTION_LANGUAGE.get());
        if (!(object instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track))
            return null;
        try {
            String key = sourceIdentity(track);
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
        source = track.source;
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
) return;
            attachTranslation(track, captured.policy, state);
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
        attachTranslation(track, primary, state);
        return true;
    }

    private static void invalidateTranslation() {
        if (translatedSession != null) translatedSession.invalidate();
        translatedSession = null; translatedPolicy = null; translatedKey = null; translatedToken = null;
        translatedStates.clear();
    }

    private static void attachTranslation(Track track, StableCaptionBlockPolicy policy,
            StableCaptionBlocks.State state) {
        if (state == null) return;
        String primary = PreferredCaptionLanguagePatch.blockTranslationTarget(track.language,
                Settings.PREFERRED_CAPTION_LANGUAGE.get(), PreferredCaptionLanguagePatch.hasUserSelectedTrack(),
                Requester.getAppLocale());
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

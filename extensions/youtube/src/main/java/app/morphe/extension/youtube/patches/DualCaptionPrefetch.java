/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.util.Log;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads a second caption file through the existing native queue, without selecting it. */
public final class DualCaptionPrefetch {
    /** Implemented on the existing native subtitle handler by the patch. */
    public interface Host {
        Object patch_getManager();

        boolean patch_supportsPrefetch(Object track);

        void patch_prefetch(Object track, PassiveCallback callback);
    }

    private static WeakReference<Host> host = new WeakReference<>(null);
    private static final LinkedHashMap<String, PassiveCallback> attempts = new LinkedHashMap<>();
    private static String video, selectedSource;
    private static int issuedRequests;
    private static boolean failureNotified;
    private static long generation;

    private DualCaptionPrefetch() {}

    /** Constructor hook: keep no strong reference to a player or its native loader. */
    public static synchronized void capture(Object object) {
        if (!(object instanceof Host next)) return;
        if (host.get() != next) {
            host = new WeakReference<>(next);
            attempts.clear();
            issuedRequests = 0;
            failureNotified = false;
            selectedSource = null;
            generation++;
        }
    }

    /** Video hook also invalidates requests when captions are disabled on the next video. */
    public static synchronized void onVideoId(String id) {
        DualCaptionTracks.onVideoId(id);
        if (id == null || !id.equals(video)) {
            video = id;
            attempts.clear();
            issuedRequests = 0;
            failureNotified = false;
            selectedSource = null;
            generation++;
        }
    }

    /** Called only from native selected-track loads; never schedules a retry by itself. */
    public static void onPrimaryTrack(Object object) {
        if (!StableCaptionBlocks.isSupported()) return;
        DualCaptionTracks.onSelectedTrack(object);
        if (StableCaptionBlocks.blockTranslationEnabled()) return;
        String target = AutoTranslateCaptionsPatch.translationLanguage(
                Settings.SECONDARY_CAPTION_LANGUAGE.get());
        if (target == null || !(object instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface primary))
            return;
        try {
            String source = sourceIdentity(primary);
            if (source == null) return;
            String id = AutoTranslateCaptionsPatch.captionParameters(primary.patch_getUrl()).get("v");
            String key = source + "|" + AutoTranslateCaptionsPatch.getLanguageKey(target);
            final PassiveCallback attempt;
            synchronized (DualCaptionPrefetch.class) {
                Host current = host.get();
                if (current == null) return;
                if (!id.equals(video)) {
                    video = id;
                    attempts.clear();
                    issuedRequests = 0;
                    failureNotified = false;
                    selectedSource = null;
                    generation++;
                }
                if (!source.equals(selectedSource)) {
                    selectedSource = source;
                    attempts.clear();
                    failureNotified = false;
                    generation++;
                }
                // Source changes must invalidate queued work even when no translation is needed.
                if (AutoTranslateCaptionsPatch.getLanguageKey(target).equals(
                        AutoTranslateCaptionsPatch.getLanguageKey(primary.patch_getLanguageCode()))) return;
                PassiveCallback previous = attempts.get(key);
                if (previous != null && (!previous.finished || !previous.retryable || previous.number >= 2))
                    return;
                if (previous == null && attempts.size() >= 8) return;
                attempt = new PassiveCallback(generation, id, source, target, current, key,
                        previous == null ? 1 : previous.number + 1);
                attempts.put(key, attempt);
            }
            Utils.runOnMainThread(() -> attempt.load(primary));
        } catch (RuntimeException | LinkageError ignored) {
            // Caption display must survive optional secondary-loading failures.
        }
    }

    private static synchronized boolean isCurrent(PassiveCallback attempt) {
        return !attempt.finished && matchesCurrent(attempt);
    }

    /** Called while holding the prefetch lock, including after a failed attempt completes. */
    private static boolean matchesCurrent(PassiveCallback attempt) {
        Host expected = attempt.expected.get();
        return expected != null && host.get() == expected && generation == attempt.token
                && attempt.id.equals(video) && attempt.id.equals(VideoInformation.getVideoId())
                && attempt.source.equals(selectedSource) && attempts.get(attempt.key) == attempt
                && attempt.target.equals(AutoTranslateCaptionsPatch.translationLanguage(
                        Settings.SECONDARY_CAPTION_LANGUAGE.get()));
    }

    private static void showFailure(PassiveCallback attempt) {
        Utils.runOnMainThread(() -> {
            synchronized (DualCaptionPrefetch.class) {
                if (!matchesCurrent(attempt) || failureNotified) return;
                failureNotified = true;
            }
            Utils.showToastShort(str("morphe_secondary_captions_unavailable"));
        });
    }

    /** Native callback interface and its two bridge methods are attached during patching. */
    public static final class PassiveCallback {
        private final long token;
        private final String id, source, target, key;
        private final WeakReference<Host> expected;
        private final int number;
        private boolean finished, retryable;

        PassiveCallback(long token, String id, String source, String target, Host host,
                String key, int number) {
            this.token = token;
            this.id = id;
            this.source = source;
            this.target = target;
            this.key = key;
            this.number = number;
            this.expected = new WeakReference<>(host);
        }

        private void load(AutoTranslateCaptionsPatch.CaptionTrackInterface primary) {
            synchronized (DualCaptionPrefetch.class) {
                if (!isCurrent(this)) {
                    attempts.remove(key, this);
                    return;
                }
            }
            try {
                Host active = expected.get();
                if (active == null) return;
                AutoTranslateCaptionsPatch.CaptionTrackInterface secondary = deriveTrack(
                        primary, target, active.patch_getManager(), Requester.getAppLocale());
                if (secondary == null) {
                    fail(true, "track unavailable");
                    return;
                }
                if (!active.patch_supportsPrefetch(secondary)) {
                    fail(false, "unsupported");
                    return;
                }
                synchronized (DualCaptionPrefetch.class) {
                    if (!isCurrent(this)) {
                        attempts.remove(key, this);
                        return;
                    }
                    // The per-video bound survives repeated source changes.
                    if (issuedRequests >= 16) {
                        fail(false, "request limit");
                        return;
                    }
                    issuedRequests++;
                }
                active.patch_prefetch(secondary, this);
                diagnostic("queued", token);
            } catch (RuntimeException | LinkageError ignored) {
                fail(true, "enqueue failed");
            }
        }

        private boolean finish(boolean canRetry) {
            synchronized (DualCaptionPrefetch.class) {
                if (!isCurrent(this)) return false;
                finished = true;
                retryable = canRetry;
                return true;
            }
        }

        private void fail(boolean canRetry, String status) {
            if (finish(canRetry)) {
                diagnostic(status, token);
                showFailure(this);
            }
        }

        public void onSuccess(Object request, Object result) {
            if (result == null) {
                fail(true, "empty completion");
            } else if (finish(false)) {
                // Cached timelines may complete without decoding XML again.
                DualCaptionTracks.onNativeResult(request, result);
                diagnostic("native ready", token);
            }
        }

        public void onFailure(Object request, Exception error) {
            fail(true, "native failed");
        }
    }

    private static void diagnostic(String status, long token) {
        Log.i("MorpheDualCaptions", "Secondary " + status + " generation=" + token);
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

    /** Pure track derivation: preserve every URL byte except the validated target value. */
    static AutoTranslateCaptionsPatch.CaptionTrackInterface deriveTrack(
            AutoTranslateCaptionsPatch.CaptionTrackInterface primary,
            String target,
            Object manager,
            Locale locale) {
        String identity = sourceIdentity(primary);
        String canonical = AutoTranslateCaptionsPatch.translationLanguage(target);
        if (identity == null || canonical == null) return null;
        Map<String, String> fields =
                AutoTranslateCaptionsPatch.captionParameters(primary.patch_getUrl());
        String sourceLanguage = fields.get("lang");
        if (AutoTranslateCaptionsPatch.getLanguageKey(canonical)
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(sourceLanguage))) {
            if (!(manager
                    instanceof
                    PreferredCaptionLanguagePatch.SubtitleManagerInterface nativeManager))
                return null;
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> direct =
                    nativeManager.patch_getDirectCaptionTracks();
            if (direct != null)
                for (var candidate : direct) {
                    if (candidate instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track
                            && identity.equals(sourceIdentity(track))
                            && !AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl())
                                    .containsKey("tlang")) return track;
                }
            return null;
        }
        String previous = fields.get("tlang");
        if (previous == null)
            return AutoTranslateCaptionsPatch.createTranslation(primary, canonical, locale);
        if (AutoTranslateCaptionsPatch.getLanguageKey(previous)
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(canonical))) return null;
        String url = primary.patch_getUrl();
        int query = url.indexOf('?') + 1, fragment = url.indexOf('#', query);
        int end = fragment < 0 ? url.length() : fragment;
        int cursor = query;
        while (cursor < end) {
            int amp = url.indexOf('&', cursor);
            int partEnd = amp < 0 || amp > end ? end : amp;
            if (url.startsWith("tlang=", cursor)) {
                if (!url.substring(cursor + 6, partEnd).equals(previous)) return null;
                String replaced = url.substring(0, cursor + 6) + canonical + url.substring(partEnd);
                String sourceVss = primary.patch_getVssId();
                sourceVss = sourceVss.substring(1, sourceVss.length() - previous.length() - 1);
                String label = Locale.forLanguageTag(canonical).getDisplayName(locale);
                return primary.patch_copyTranslation(
                        canonical,
                        replaced,
                        "t" + sourceVss + "." + canonical,
                        label.isEmpty() ? canonical : label);
            }
            cursor = partEnd + 1;
        }
        return null;
    }
}

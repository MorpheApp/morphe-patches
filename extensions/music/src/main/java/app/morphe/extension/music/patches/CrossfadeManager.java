package app.morphe.extension.music.patches;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Player-swap crossfade manager for YouTube Music.
 * <p>
 * Strategy: when a skip-next is detected (stopVideo reason=5), we
 * preserve the OLD ExoPlayer (which keeps playing the outgoing track)
 * and create a NEW ExoPlayer via YT Music's own factory method so it
 * has full DRM / DataSource configuration.  We swap the coordinator's
 * player to the new one so the subsequent loadVideo flow uses it.
 * Once the new track reaches STATE_READY we run a configurable
 * crossfade, then release the old player.
 * <p>
 * Multi-player fade system: when a skip arrives during an active
 * crossfade, the current incoming player is "demoted" to a quick
 * fade-out, a fresh player is created for the next track, and the
 * native loadVideo naturally loads onto it.  Multiple fade-out
 * animations run concurrently via a dedicated fading loop, each
 * player releasing when its volume reaches zero.
 * <p>
 * Each obfuscated YTM class is accessed through a dedicated interface
 * whose bridge methods are injected at patch time (same pattern as YT
 * VideoInformation).  Each interface maps 1-to-1 with an obfuscated
 * class so that when field/method names change between YTM versions,
 * only the affected interface's fingerprint and bridge methods need
 * updating.
 * @noinspection unused
 */

@SuppressWarnings("unused")
public class CrossfadeManager {

    public enum CrossFadeDuration {
        MILLISECONDS_250(250),
        MILLISECONDS_500(500),
        MILLISECONDS_750(750),
        MILLISECONDS_1000(1_000),
        MILLISECONDS_2000(2_000),
        MILLISECONDS_3000(3_000),
        MILLISECONDS_4000(4_000),
        MILLISECONDS_5000(5_000),
        MILLISECONDS_6000(6_000),
        MILLISECONDS_7000(7_000),
        MILLISECONDS_8000(8_000),
        MILLISECONDS_9000(9_000),
        MILLISECONDS_10000(10_000);

        public final int milliseconds;

        CrossFadeDuration(int milliseconds) {
            this.milliseconds = milliseconds;
        }
    }

    /**
     * Inner player coordinator (athu).
     * Holds the ExoPlayer, session, load control, shared state,
     * shared callback, video surface, and UI listener references.
     */
    public interface PlayerCoordinatorAccess {
        Object patch_getExoPlayer();
        void patch_setExoPlayer(Object player);
        Object patch_getSession();
        Object patch_getLoadControl();
        Object patch_getSharedState();
        Object patch_getSharedCallback();
        Object patch_getVideoSurface();
    }

    /**
     * ExoPlayer implementation (cpp).
     * Wraps obfuscated player method names with descriptive accessors.
     */
    public interface ExoPlayerAccess {
        int patch_getPlaybackState();
        long patch_getCurrentPosition();
        long patch_getDuration();
        void patch_setVolume(float volume);
        void patch_setPlayWhenReady(boolean play);
        void patch_release();
        Object patch_getListenerSet();
        Object patch_getInternalListener();
        void patch_setDltCallback(Object dlt);
    }

    /**
     * Session / track manager (atgd).
     */
    public interface SessionAccess {
        Object patch_getFactory();
    }

    /**
     * ExoPlayer factory (atih).
     */
    public interface PlayerFactoryAccess {
        Object patch_createPlayer(Object coordinator, Object loadControl, int flags);
    }

    /**
     * Shared playback state (crz / cup).
     */
    public interface SharedStateAccess {
        Object patch_getTimeline();
        void patch_setTimeline(Object timeline);
    }

    /**
     * Shared callback / track-selection (dll / atjx).
     */
    public interface SharedCallbackAccess {
        Object patch_getCqb();
        void patch_setCqb(Object cqb);
        Object patch_getDlt();
        void patch_setDlt(Object dlt);
    }

    /**
     * Video surface manager (atix).
     */
    public interface VideoSurfaceAccess {
        void patch_setPlayerReference(Object player);
    }

    /**
     * Outermost player delegate / MedialibPlayer (atad).
     */
    public interface MedialibPlayerAccess {
        Object patch_getPlayerChain();
        void patch_playNextInQueue();
    }

    /**
     * Audio / video toggle (nba).
     * Bridge method queries the internal state provider and returns
     * whether the player is currently in audio mode.
     */
    public interface VideoToggleAccess {
        boolean patch_isAudioMode();
        void patch_forceAudioMode();
        void patch_triggerToggle();
        void patch_forceAudioModeSilent();
        void patch_restoreVideoModeSilent();
    }

    /**
     * Delegate chain wrapper (atux).
     * Each delegate holds a reference to the next in the chain via field 'a'.
     */
    public interface DelegateAccess {
        Object patch_getDelegate();
    }

    /**
     * Listener wrapper element (cat).
     * Wraps a raw Player.Listener (bxi) inside the CopyOnWriteArraySet.
     */
    public interface ListenerWrapperAccess {
        Object patch_getWrappedListener();
    }

    /**
     * Fade curve profiles available for crossfade.
     * Uses switch instead of abstract methods to avoid anonymous inner classes,
     * which break Morphe's EnumSetting (getClass().getEnumConstants() returns null
     * for anonymous enum subclasses).
     */
    public enum FadeCurve {
        EQUAL_POWER,
        EASE_OUT_CUBIC,
        EASE_OUT_QUAD,
        SMOOTHSTEP;

        public float out(float t) {
            return switch (this) {
                case EASE_OUT_CUBIC -> 1.0f - t * t * t;
                case EASE_OUT_QUAD -> (1.0f - t) * (1.0f - t);
                case SMOOTHSTEP -> 1.0f - (3.0f * t * t - 2.0f * t * t * t);
                default -> (float) Math.cos(t * Math.PI / 2.0);
            };
        }

        public float in(float t) {
            if (this == SMOOTHSTEP) return 3.0f * t * t - 2.0f * t * t * t;
            return (float) Math.sin(t * Math.PI / 2.0);
        }
    }

    private static final boolean CROSSFADE_ENABLED = Settings.CROSSFADE_ENABLED.get();

    private static final AtomicBoolean sessionPaused = new AtomicBoolean(false);
    private static volatile boolean inVideoMode = false;
    private static volatile long manualToggleSuppressionUntil = 0;
    private static volatile boolean crossfadeInProgress = false;
    private static volatile boolean audioModeWasForced = false;
    private static volatile boolean activityRunning = false;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int TICK_MS = 50;
    private static final int READY_POLL_MS = 100;
    private static final int READY_TIMEOUT_MS = 10000;
    private static final int STATE_READY = 3;
    private static final int REASON_DIRECTOR_RESET = 5;
    private static final long AUTO_ADVANCE_THRESHOLD_MS = 5000;
    private static final long MONITOR_POLL_MS = 100;
    // Extra lead time to absorb poll granularity + new-player READY latency (~120-200ms typical).
    // Ensures the fade-out completes before the old track's audio content runs out.
    private static final long AUTO_ADVANCE_TRIGGER_BUFFER_MS = 300;
    private static final int QUICK_FADE_MS = 400;

    private static volatile SharedCallbackAccess activeSharedCallback = null;
    private static volatile ExoPlayerAccess crossfadeInPlayer = null;
    private static volatile ExoPlayerAccess pendingInPlayer = null;
    private static volatile ExoPlayerAccess pendingOutPlayer = null;
    private static volatile PlayerCoordinatorAccess activeCoordinator = null;
    private static volatile float currentFadeInVolume = 0.0f;

    private static final List<FadingPlayer> fadingOutPlayers = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean fadingLoopRunning = false;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static WeakReference<Object> lastNbaRef = new WeakReference<>(null);
    private static final boolean internalToggle = false;
    private static volatile boolean internalPlayNext = false;
    private static Runnable autoAdvanceMonitorRunnable = null;

    private static int playersCreated = 0;
    private static int playersReleased = 0;
    private static final List<WeakReference<View>> longPressRefs = new ArrayList<>();

    /**
     * Tracks a single player's fade-out animation.
     * Supports both curve-based fades (original outgoing player)
     * and linear fades (demoted incoming players during chained skips).
     */
    private static class FadingPlayer {
        final ExoPlayerAccess player;
        final float startVolume;
        final long startTimeMs;
        final long fadeDurationMs;
        final FadeCurve curve;

        /** Curve-based fade-out for the original outgoing player. */
        FadingPlayer(ExoPlayerAccess player, long fadeDurationMs, FadeCurve curve) {
            this.player = player;
            this.startVolume = 1.0f;
            this.startTimeMs = System.currentTimeMillis();
            this.fadeDurationMs = fadeDurationMs;
            this.curve = curve;
        }

        /** Linear fade-out from current volume for demoted incoming players. */
        FadingPlayer(ExoPlayerAccess player, float startVolume, long fadeDurationMs) {
            this.player = player;
            this.startVolume = Math.max(0.0f, Math.min(1.0f, startVolume));
            this.startTimeMs = System.currentTimeMillis();
            this.fadeDurationMs = Math.max(50, fadeDurationMs);
            this.curve = null;
        }

        float currentVolume() {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            float t = Math.min(1.0f, (float) elapsed / fadeDurationMs);
            if (curve != null) {
                return curve.out(t);
            }
            return startVolume * (1.0f - t);
        }

        boolean isComplete() {
            return System.currentTimeMillis() - startTimeMs >= fadeDurationMs;
        }
    }

    private static int lastLoggedReason = -1;
    private static int suppressedReasonCount = 0;

    /**
     * Injection point.
     */
    public static boolean onBeforeStopVideo(Object atadInstance, int reason) {
        if (!CROSSFADE_ENABLED) return false;

        lastAtadRef = new WeakReference<>(atadInstance);
        tryAttachLongPressHandler();

        if (crossfadeInProgress) {
            if (reason == REASON_DIRECTOR_RESET) {
                return handleChainedSkip(atadInstance);
            }
            Logger.printDebug(() -> "stopVideo(" + reason + "): BLOCKED — crossfade in progress");
            return true;
        }

        if (reason != REASON_DIRECTOR_RESET) {
            if (reason == lastLoggedReason) {
                suppressedReasonCount++;
            } else {
                if (suppressedReasonCount > 0) {
                    Logger.printDebug(() -> "  (suppressed " + suppressedReasonCount
                                        + " duplicate reason=" + lastLoggedReason + " entries)");
                }
                Logger.printDebug(() -> "stopVideo reason=" + reason + " — not a skip, ignoring");
                lastLoggedReason = reason;
                suppressedReasonCount = 0;
            }
            return false;
        }
        lastLoggedReason = -1;
        suppressedReasonCount = 0;

        if (System.currentTimeMillis() < manualToggleSuppressionUntil) {
            Logger.printDebug(() -> "stopVideo(5): skip — within manual toggle suppression window");
            return false;
        }

        if (sessionPaused.get() || getCrossfadeDurationMs() <= 0) {
            Logger.printDebug(() -> "stopVideo(5): skip [paused=" + sessionPaused.get()
                    + " inVideo=" + isCurrentlyInVideoMode() + "]");
            return false;
        }

        if (isFromTaskRemoval()) {
            Logger.printDebug(() -> "stopVideo(5): skip — triggered by onTaskRemoved (activity killed)");
            if (crossfadeInProgress) cleanupAllPlayers();
            return false;
        }

        try {
            PlayerCoordinatorAccess coordinator = getCoordinatorFromAtad(atadInstance);
            if (coordinator == null) {
                Logger.printException(() -> "Could not find coordinator from atad");
                return false;
            }

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) {
                Logger.printException(() -> "Coordinator ExoPlayer is null");
                return false;
            }

            boolean isAutoAdvance = false;
            try {
                long pos = currentExo.patch_getCurrentPosition();
                long duration = currentExo.patch_getDuration();
                long remaining = (duration > 0) ? duration - pos : Long.MAX_VALUE;
                isAutoAdvance = duration > 0 && remaining >= 0
                        && remaining < AUTO_ADVANCE_THRESHOLD_MS;
                final boolean finalIsAutoAdvance = isAutoAdvance;
                Logger.printDebug(() -> "stopVideo(5): pos=" + pos + "ms dur=" + duration
                        + "ms remaining=" + remaining + "ms → "
                        + (finalIsAutoAdvance ? "AUTO-ADVANCE" : "MANUAL SKIP"));
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not read position/duration, assuming manual skip", ex);
            }

            if (isAutoAdvance && !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
                Logger.printDebug(() -> "stopVideo(5): skip — auto-advance crossfade disabled");
                return false;
            }
            if (!isAutoAdvance && !Settings.CROSSFADE_ON_SKIP.get()) {
                Logger.printDebug(() -> "stopVideo(5): skip — manual skip crossfade disabled");
                return false;
            }

            boolean wasInVideoMode = isCurrentlyInVideoMode();

            Logger.printDebug(() -> "stopVideo(5): STARTING crossfade [paused=" + sessionPaused.get()
                    + " wasInVideo=" + wasInVideoMode + "]");

            Logger.printDebug(() -> "Current player state=" + currentExo.patch_getPlaybackState()
                    + " class=" + currentExo.getClass().getName());

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                Logger.printDebug(() -> "Silent audio mode set BEFORE factory (video→audio, no nmi broadcast)");
            }

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;

            coordinator.patch_setExoPlayer(newExo);
            Logger.printDebug(() -> "Swapped coordinator ExoPlayer → new player");

            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
                Logger.printDebug(() -> "Updated video surface → new player");
            }

            Logger.printDebug(() -> "Old player preserved (keeps playing), polling for new track ready"
                        + " - BLOCKING native stopVideo");
            pollForNewTrackReady(newExo);

            return true;

        } catch (Exception ex) {
            Logger.printException(() -> "onBeforeStopVideo error", ex);
            cleanupAllPlayers();
            if (audioModeWasForced) {
                audioModeWasForced = false;
                restoreVideoModeSilently();
            }
            return false;
        }
    }

    /**
     * Handles a skip-next that arrives while a crossfade is already in progress.
     * Demotes the current incoming player to a quick fade-out, creates a new
     * player, and swaps it onto the coordinator so the native loadVideo flow
     * naturally loads the next track onto it.
     */
    private static boolean handleChainedSkip(Object atadInstance) {
        Logger.printDebug(() -> "stopVideo(5): CHAINED SKIP — creating new player, deferring demotion until READY");

        if (sessionPaused.get() || getCrossfadeDurationMs() <= 0) {
            Logger.printDebug(() -> "Chained skip: crossfade now disabled/paused — aborting crossfade");
            abortCrossfadeNow();
            return false;
        }

        try {
            PlayerCoordinatorAccess coordinator = activeCoordinator;
            if (coordinator == null) {
                coordinator = getCoordinatorFromAtad(atadInstance);
                if (coordinator == null) {
                    Logger.printException(() -> "Chained skip: coordinator null — aborting");
                    abortCrossfadeNow();
                    return false;
                }
            }

            ExoPlayerAccess oldPending = pendingInPlayer;
            if (oldPending != null) {
                Logger.printDebug(() -> "Chained skip: releasing previous pending player @"
                        + System.identityHashCode(oldPending)
                        + " (never reached READY)");
                releasePlayer(oldPending);
            }

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                Logger.printException(() -> "Chained skip: factory failed — aborting crossfade");
                abortCrossfadeNow();
                return false;
            }

            newExo.patch_setVolume(0.0f);
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;

            coordinator.patch_setExoPlayer(newExo);
            Logger.printDebug(() -> "Chained skip: swapped coordinator → new player @"
                    + System.identityHashCode(newExo)
                    + " (current animation continues uninterrupted)");

            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
            }

            pollForNewTrackReady(newExo);

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "handleChainedSkip error", ex);
            abortCrossfadeNow();
            return false;
        }
    }

    /**
     * Creates a new ExoPlayer via the factory, handling shared state
     * null-out and post-creation validation.
     * Returns null on failure (caller should abort/fallback).
     */
    private static ExoPlayerAccess createNewPlayer(PlayerCoordinatorAccess coordinator) {
        try {
            SessionAccess session = (SessionAccess) coordinator.patch_getSession();
            if (session == null) {
                Logger.printException(() -> "createNewPlayer: session null");
                return null; }

            PlayerFactoryAccess factory = (PlayerFactoryAccess) session.patch_getFactory();
            if (factory == null) {
                Logger.printException(() -> "createNewPlayer: factory null");
                return null; }

            Object loadControl = coordinator.patch_getLoadControl();
            if (loadControl == null) {
                Logger.printException(() -> "createNewPlayer: loadControl null");
                return null; }

            SharedStateAccess sharedState = (SharedStateAccess) coordinator.patch_getSharedState();
            if (sharedState == null) {
                Logger.printException(() -> "createNewPlayer: sharedState null");
                return null; }

            SharedCallbackAccess sharedCallback =
                    (SharedCallbackAccess) coordinator.patch_getSharedCallback();
            if (sharedCallback == null) {
                Logger.printException(() -> "createNewPlayer: sharedCallback null");
                return null; }
            activeSharedCallback = sharedCallback;

            Object oldTimeline = sharedState.patch_getTimeline();
            Object oldCqb = sharedCallback.patch_getCqb();
            Logger.printDebug(() -> "Pre-factory shared state: cqb=" + (oldCqb != null));
            sharedState.patch_setTimeline(null);
            sharedCallback.patch_setCqb(null);

            ExoPlayerAccess newExo = createPlayerViaFactory(factory, coordinator, loadControl);
            if (newExo == null) {
                Logger.printException(() -> "Factory returned null — restoring");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            Object postTimeline = sharedState.patch_getTimeline();
            Object postCqb = sharedCallback.patch_getCqb();
            Logger.printDebug(() -> "Post-factory shared state: cqb=" + (postCqb != null)
                    + " newExo=" + System.identityHashCode(newExo));
            if (postTimeline == null) {
                Logger.printException(() -> "Factory failed to set timeline — aborting");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }
            if (postCqb == null) {
                Logger.printException(() -> "Factory failed to set cqb — aborting");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            return newExo;
        } catch (Exception ex) {
            Logger.printException(() -> "createNewPlayer error", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Returns true to BLOCK the native playNextInQueue, false to allow it.
     * <p>
     * Strategy: we block the original call, set up our crossfade state, then
     * invoke playNextInQueue again via patch_playNextInQueue with internalPlayNext=true.
     * That second call passes through immediately (returns false), allowing the native
     * to load the next track onto our new player. We then synchronously re-enforce
     * volume=0 right after the native returns — eliminating the blip that occurred
     * in the void-hook design where the native ran in the 100ms poll window.
     */
    public static boolean onBeforePlayNext(Object coordinatorInstance) {
        if (!CROSSFADE_ENABLED) return false;

        // Internal re-invoke: let native through immediately.
        if (internalPlayNext) {
            internalPlayNext = false;
            return false;
        }

        Logger.printDebug(() -> "onBeforePlayNext called");
        tryAttachLongPressHandler();

        if (sessionPaused.get() || getCrossfadeDurationMs() <= 0
                || crossfadeInProgress || !activityRunning) {
            return false;
        }

        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            Logger.printDebug(() -> "PlayNext: skip — auto-advance crossfade disabled");
            return false;
        }

        try {
            boolean wasInVideoMode = isCurrentlyInVideoMode();

            PlayerCoordinatorAccess coordinator =
                    (PlayerCoordinatorAccess) coordinatorInstance;

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) return false;

            int currentState = currentExo.patch_getPlaybackState();
            Logger.printDebug(() -> "PlayNext: current player state=" + currentState
                        + " wasInVideo=" + wasInVideoMode);

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;

            coordinator.patch_setExoPlayer(newExo);
            Logger.printDebug(() -> "PlayNext: swapped coordinator ExoPlayer → new player");

            VideoSurfaceAccess surface =
                    (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
                Logger.printDebug(() -> "PlayNext: updated video surface → new player");
            }

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                Logger.printDebug(() -> "PlayNext: forced audio mode for incoming track (was in video mode)");
            }

            // Re-invoke natively so the next track actually loads onto the new player.
            // internalPlayNext=true causes the hook to pass through immediately.
            // We then re-enforce volume=0 synchronously, before any poll tick.
            Object atad = lastAtadRef.get();
            if (atad instanceof MedialibPlayerAccess medialibPlayerAccessObj) {
                internalPlayNext = true;
                try {
                    medialibPlayerAccessObj.patch_playNextInQueue();
                } catch (Exception ex) {
                    internalPlayNext = false;
                    Logger.printDebug(() -> "PlayNext: re-invoke threw exception", ex);
                }
                try {
                    newExo.patch_setVolume(0.0f);
                    Logger.printDebug(() -> "PlayNext: volume re-enforced to 0 after native");
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Ignoring patch_setVolume exception", ex);
                }
            } else {
                Logger.printDebug(() -> "PlayNext: atad ref lost — cannot re-invoke native");
            }

            Logger.printDebug(() -> "PlayNext: old player preserved, polling for new track ready");
            pollForNewTrackReady(newExo);
            return true; // block original call

        } catch (Exception ex) {
            Logger.printException(() -> "onBeforePlayNext error", ex);
            cleanupAllPlayers();
            if (audioModeWasForced) {
                audioModeWasForced = false;
                restoreVideoModeSilently();
            }
            return false;
        }
    }

    private static long lastPauseEventMs = 0;
    private static long lastPlayEventMs = 0;
    private static final long EVENT_DEDUP_WINDOW_MS = 100;

     /**
     * Injection point.
     * <p>
     * Hooked at the top of MedialibPlayer.pauseVideo.
     * Returns true to BLOCK the pause, false to allow.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean onPauseVideo() {
        if (!CROSSFADE_ENABLED) return false;

        long now = System.currentTimeMillis();
        if (now - lastPauseEventMs < EVENT_DEDUP_WINDOW_MS) return false;
        lastPauseEventMs = now;

        if (!crossfadeInProgress) {
            return false;
        }

        Logger.printDebug(() -> "onPauseVideo during crossfade — aborting crossfade, allowing pause");
        abortCrossfadeNow();
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Hooked at the top of MedialibPlayer.playVideo.
     */
    public static void onPlayVideo(Object atadInstance) {
        if (!CROSSFADE_ENABLED) return;

        long now = System.currentTimeMillis();
        if (now - lastPlayEventMs < EVENT_DEDUP_WINDOW_MS) return;
        lastPlayEventMs = now;

        if (atadInstance != null) {
            lastAtadRef = new WeakReference<>(atadInstance);
        }

        Logger.printDebug(() -> "onPlayVideo [crossfading=" + crossfadeInProgress
                + ", atad=" + (atadInstance != null) + "]");
        if (!crossfadeInProgress) {
            startAutoAdvanceMonitor();
        }
    }

    private static int lastPollState = -1;

    private static void pollForNewTrackReady(final ExoPlayerAccess newPlayer) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        lastPollState = -1;

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!crossfadeInProgress) return;
                if (newPlayer != pendingInPlayer) return;

                // Keep new player silent while waiting for READY. The native
                // playNextInQueue (auto-advance) runs after our void hook and
                // resets the player to volume 1.0 — re-enforce on every tick.
                try {
                    newPlayer.patch_setVolume(0.0f);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Ignoring pollForNewTrackReady exception", ex);
                }

                try {
                    int state = newPlayer.patch_getPlaybackState();
                    if (state == STATE_READY) {
                        Logger.printDebug(() -> "Pending track READY — promoting to crossfade");
                        onPendingPlayerReady(newPlayer);
                        return;
                    }

                    if (state == 4) {
                        Logger.printException(() -> "Pending player ENDED unexpectedly — aborting");
                        cleanupAllPlayers();
                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            restoreVideoModeSilently();
                        }
                        return;
                    }

                    if (state != lastPollState) {
                        Logger.printDebug(() -> "Poll: state → " + state);
                        lastPollState = state;
                    }

                    if (System.currentTimeMillis() > deadline) {
                        Logger.printException(() -> "Timeout waiting for new track");
                        cleanupAllPlayers();
                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            restoreVideoModeSilently();
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, READY_POLL_MS);
                } catch (Exception ex) {
                    Logger.printException(() -> "Poll error", ex);
                    cleanupAllPlayers();
                    if (audioModeWasForced) {
                        audioModeWasForced = false;
                        restoreVideoModeSilently();
                    }
                }
            }
        }, READY_POLL_MS);
    }

    /**
     * Called when a pending player reaches STATE_READY.
     * Moves the outgoing player(s) to the fade-out list and
     * promotes the pending player to the active crossfade-in role.
     */
    private static void onPendingPlayerReady(ExoPlayerAccess newPlayer) {
        FadeCurve curve = Settings.CROSSFADE_CURVE.get();
        long fadeDuration = getCrossfadeDurationMs();

        ExoPlayerAccess outgoing = pendingOutPlayer;
        if (outgoing != null) {
            // Match fade-out duration to actual remaining audio on the outgoing track.
            // This is critical for auto-advance: the trigger fires 300ms+ before the
            // configured fade duration, but READY latency is variable (100-500ms+).
            // Without this adjustment, the fade-out may start too late, causing the
            // outgoing track to end at non-zero volume (perceptible cutoff).
            long fadeOutDuration = fadeDuration;
            try {
                long pos = outgoing.patch_getCurrentPosition();
                long dur = outgoing.patch_getDuration();
                if (dur > 0 && pos >= 0) {
                    long actualRemaining = dur - pos;
                    Logger.printDebug(() -> "onPendingPlayerReady: outgoing remaining=" + actualRemaining
                                        + "ms fadeDuration=" + fadeDuration + "ms");
                    if (actualRemaining < fadeDuration) {
                        fadeOutDuration = Math.max(150, actualRemaining);
                        final long fadeOutDurationFinal = fadeOutDuration;
                        Logger.printDebug(() -> "Fade-out shortened to " + fadeOutDurationFinal
                                                + "ms to match remaining audio (was " + fadeOutDurationFinal + "ms)");
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not read outgoing remaining time", ex);
            }
            fadingOutPlayers.add(new FadingPlayer(outgoing, fadeOutDuration, curve));
            pendingOutPlayer = null;

            final long fadeOutDurationFinal = fadeOutDuration;
            Logger.printDebug(() -> "Original outgoing player @" + System.identityHashCode(outgoing)
                    + " → fade-out list (" + fadeOutDurationFinal + "ms)");
        }

        ExoPlayerAccess prevIncoming = crossfadeInPlayer;
        if (prevIncoming != null && prevIncoming != newPlayer) {
            float vol = currentFadeInVolume;
            long quickDuration = Math.max(200, (long) (QUICK_FADE_MS * vol));
            if (vol > 0.01f) {
                fadingOutPlayers.add(new FadingPlayer(prevIncoming, vol, quickDuration));
                Logger.printDebug(() -> "Previous incoming player @"
                        + System.identityHashCode(prevIncoming)
                        + " → quick fade-out from " + String.format(Locale.US, "%.2f", vol)
                        + " over " + quickDuration + "ms");
            } else {
                releasePlayer(prevIncoming);
                Logger.printDebug(() -> "Previous incoming player @"
                        + System.identityHashCode(prevIncoming)
                        + " → released (vol ≈ 0)");
            }
        }

        crossfadeInPlayer = newPlayer;
        pendingInPlayer = null;
        currentFadeInVolume = 0.0f;

        ensureFadingLoopRunning();
        animateCrossfade(newPlayer);
    }

    private static void startAutoAdvanceMonitor() {
        stopAutoAdvanceMonitor();
        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) return;

        autoAdvanceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (sessionPaused.get() || !activityRunning
                        || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()
                        || crossfadeInProgress) {
                    return;
                }

                Object atad = lastAtadRef.get();
                if (atad == null) {
                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                    return;
                }

                try {
                    PlayerCoordinatorAccess coordinator = getCoordinatorQuiet(atad);
                    if (coordinator == null) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }
                    ExoPlayerAccess exo =
                            (ExoPlayerAccess) coordinator.patch_getExoPlayer();
                    if (exo == null) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    int state = exo.patch_getPlaybackState();
                    if (state != STATE_READY) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    long pos = exo.patch_getCurrentPosition();
                    long dur = exo.patch_getDuration();
                    if (dur <= 0) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    long remaining = dur - pos;
                    long fadeDuration = getCrossfadeDurationMs();

                    if (remaining % 5000 < MONITOR_POLL_MS) {
                        Logger.printDebug(() -> "Auto-advance monitor: pos=" + pos
                                                + "ms dur=" + dur + "ms remaining=" + remaining
                                                + "ms trigger@" + (fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS) + "ms");
                    }

                    if (dur <= fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    if (remaining <= fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS && remaining > 0) {
                        Logger.printDebug(() -> "Auto-advance: triggering playNextInQueue"
                                                + " at remaining=" + remaining
                                                + "ms (fadeDuration=" + fadeDuration + "ms)");
                        stopAutoAdvanceMonitor();
                        try {
                            ((MedialibPlayerAccess) atad).patch_playNextInQueue();
                        } catch (Exception ex) {
                            Logger.printDebug(() -> "Ignoring playNextInQueue exceptoin", ex);
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Auto-advance monitor error", ex);
                    mainHandler.postDelayed(this, MONITOR_POLL_MS * 2);
                }
            }
        };
        mainHandler.postDelayed(autoAdvanceMonitorRunnable, MONITOR_POLL_MS);
        Logger.printDebug(() -> "Auto-advance monitor started");
    }

    private static void stopAutoAdvanceMonitor() {
        if (autoAdvanceMonitorRunnable != null) {
            mainHandler.removeCallbacks(autoAdvanceMonitorRunnable);
            autoAdvanceMonitorRunnable = null;
        }
    }

    private static void abortCrossfadeNow() {
        if (!crossfadeInProgress) return;

        ExoPlayerAccess inp = crossfadeInPlayer;
        ExoPlayerAccess pending = pendingInPlayer;
        ExoPlayerAccess pendOut = pendingOutPlayer;
        PlayerCoordinatorAccess coord = activeCoordinator;

        ExoPlayerAccess bestPlayer = null;
        boolean inpReady = false;
        if (inp != null) {
            try {
                inpReady = inp.patch_getPlaybackState() == STATE_READY;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Ignoring in.patch_getPlaybackState exception", ex);
            }
        }
        boolean pendingReady = false;
        if (pending != null) {
            try {
                pendingReady = pending.patch_getPlaybackState() == STATE_READY;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Ignoring pending.patch_getPlaybackState exception", ex);
            }
        }

        if (pendingReady) {
            bestPlayer = pending;
        } else if (inpReady) {
            bestPlayer = inp;
        } else if (pendOut != null) {
            bestPlayer = pendOut;
        }

        if (bestPlayer != null && coord != null) {
            final ExoPlayerAccess bestPlayerFinal = bestPlayer;
            Logger.printDebug(() -> "abortCrossfadeNow: snapping to player @"
                    + System.identityHashCode(bestPlayerFinal));
            try {
                bestPlayer.patch_setVolume(1.0f);
                bestPlayer.patch_setPlayWhenReady(true);
                coord.patch_setExoPlayer(bestPlayer);
                VideoSurfaceAccess surface =
                        (VideoSurfaceAccess) coord.patch_getVideoSurface();
                if (surface != null) surface.patch_setPlayerReference(bestPlayer);
            } catch (Exception ex) {
                Logger.printDebug(() -> "abortCrossfadeNow: snap failed", ex);
            }
        }

        if (inp != null && inp != bestPlayer) releasePlayer(inp);
        if (pending != null && pending != bestPlayer) releasePlayer(pending);
        if (pendOut != null && pendOut != bestPlayer) releasePlayer(pendOut);

        releaseAllFadingPlayers();

        crossfadeInPlayer = null;
        pendingInPlayer = null;
        pendingOutPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        currentFadeInVolume = 0.0f;

        if (audioModeWasForced) {
            audioModeWasForced = false;
            restoreVideoModeSilently();
        }
    }

    /**
     * Fade-in animation for the active crossfade-in player.
     * Fade-outs are managed independently by the fading loop.
     * Self-terminates if this player is superseded by a chained skip.
     */
    private static void animateCrossfade(final ExoPlayerAccess inPlayer) {
        // Re-enforce volume=0 before unmuting the player. For auto-advance,
        // the native playNextInQueue runs after our hook and may reset the
        // volume to 1.0. For manual-skip the native is blocked, so the
        // initial patch_setVolume(0) holds — but we re-enforce here for both.
        try {
            inPlayer.patch_setVolume(0.0f);
            Logger.printDebug(() -> "fade-in pre-start: @" + System.identityHashCode(inPlayer)
                    + " volume enforced to 0 before setPlayWhenReady");
        } catch (Exception ex) {
            Logger.printDebug(() -> "fade-in pre-start: failed to zero volume", ex);
        }

        try {
            inPlayer.patch_setPlayWhenReady(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Ignoring inPlayer.patch_setPlayWhenReady exception", ex);
        }

        final long startTime = System.currentTimeMillis();
        final long duration = getCrossfadeDurationMs();

        Logger.printDebug(() -> "Crossfade fade-in started for @" + System.identityHashCode(inPlayer)
                + ", duration=" + duration + "ms"
                + ", fading-out players=" + fadingOutPlayers.size());

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!crossfadeInProgress) return;
                if (inPlayer != crossfadeInPlayer) return;

                long elapsed = System.currentTimeMillis() - startTime;
                float t = Math.min(1.0f, (float) elapsed / duration);

                FadeCurve curve = Settings.CROSSFADE_CURVE.get();
                float inVol = curve.in(t);
                currentFadeInVolume = inVol;

                try {
                    inPlayer.patch_setVolume(inVol);
                    if (elapsed % 500 < TICK_MS) {
                        int inState = inPlayer.patch_getPlaybackState();
                        Logger.printDebug(() -> String.format(Locale.US,
                                "fade-in: t=%.2f inVol=%.2f(st=%d) fadingOut=%d",
                                t, inVol, inState, fadingOutPlayers.size()));
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Fade-in tick error", ex);
                }

                if (t < 1.0f) {
                    mainHandler.postDelayed(this, TICK_MS);
                } else {
                    Logger.printDebug(() -> "Fade-in complete for @" + System.identityHashCode(inPlayer));
                    inVideoMode = false;
                    currentFadeInVolume = 1.0f;
                    try {
                        inPlayer.patch_setVolume(1.0f);
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "Ignoring inPlayer.patch_setVolume exception", ex);
                    }

                    if (pendingInPlayer == null) {
                        crossfadeInProgress = false;
                        crossfadeInPlayer = null;
                        activeCoordinator = null;

                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            mainHandler.post(() -> {
                                if (crossfadeInProgress) return;
                                restoreVideoModeSilently();
                            });
                        }

                        startAutoAdvanceMonitor();
                    } else {
                        Logger.printDebug(() -> "Fade-in complete but pending player exists - "
                                + "waiting for it to reach READY");
                    }
                }
            }
        });
    }

    private static ExoPlayerAccess createPlayerViaFactory(
            PlayerFactoryAccess factory,
            PlayerCoordinatorAccess coordinator,
            Object loadControl) {
        try {
            Object player = factory.patch_createPlayer(coordinator, loadControl, 0);
            if (player != null) {
                playersCreated++;
                Logger.printDebug(() -> "Factory created player @"
                        + System.identityHashCode(player)
                        + " [created=" + playersCreated
                        + " released=" + playersReleased
                        + " outstanding="
                        + (playersCreated - playersReleased) + "]");
            }
            return (ExoPlayerAccess) player;
        } catch (Exception ex) {
            Logger.printException(() -> "createPlayerViaFactory failed", ex);
            return null;
        }
    }

    private static boolean isFromTaskRemoval() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("onTaskRemoved".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    /**
     * Quiet variant — no traversal logging.
     */
    private static PlayerCoordinatorAccess getCoordinatorQuiet(Object atadInstance) {
        try {
            MedialibPlayerAccess atad = (MedialibPlayerAccess) atadInstance;
            Object chain = atad.patch_getPlayerChain();
            if (chain == null) return null;

            while (chain instanceof DelegateAccess) {
                Object delegate = ((DelegateAccess) chain).patch_getDelegate();
                if (delegate == null || delegate == chain) break;
                chain = delegate;
            }

            if (chain instanceof PlayerCoordinatorAccess) {
                return (PlayerCoordinatorAccess) chain;
            }
            return null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "getCoordinatorQuiet failure", ex);
            return null;
        }
    }

    /**
     * Walks the delegate chain from atad to the innermost player
     * coordinator that holds the ExoPlayer reference.
     */
    private static PlayerCoordinatorAccess getCoordinatorFromAtad(
            Object atadInstance) {
        try {
            MedialibPlayerAccess atad = (MedialibPlayerAccess) atadInstance;
            Object chain = atad.patch_getPlayerChain();
            if (chain == null) {
                Logger.printException(() -> "atad player chain is null");
                return null;
            }

            int depth = 0;
            while (chain instanceof DelegateAccess) {
                Object delegate = ((DelegateAccess) chain).patch_getDelegate();
                if (delegate == null || delegate == chain) break;
                chain = delegate;
                depth++;
            }


            final int depthFinal = depth;
            final Object chainFinal = chain;
            Logger.printDebug(() -> "Traversed " + depthFinal + " delegates → "
                    + chainFinal.getClass().getName());

            if (chain instanceof PlayerCoordinatorAccess) {
                return (PlayerCoordinatorAccess) chain;
            }

            final Object chainFinal2 = chain;
            Logger.printException(() -> "Innermost class is not a PlayerCoordinatorAccess: "
                    + chainFinal2.getClass().getName());
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "getCoordinatorFromAtad error", ex);
            return null;
        }
    }

    private static void releasePlayer(ExoPlayerAccess p) {
        if (p == null) return;

        playersReleased++;
        Logger.printDebug(() -> "releasePlayer: @" + System.identityHashCode(p)
                + " [created=" + playersCreated + " released=" + playersReleased
                + " outstanding=" + (playersCreated - playersReleased) + "]");

        SharedCallbackAccess callback = activeSharedCallback;
        Object savedCqb = null, savedDlt = null;
        if (callback != null) {
            savedCqb = callback.patch_getCqb();
            savedDlt = callback.patch_getDlt();
        }

        try {
            p.patch_setDltCallback(null);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Ignoring p.patch_setDltCallback exception", ex);
        }

        try {
            p.patch_release();
        } catch (Exception ex) {
            Logger.printDebug(() -> "releasePlayer: release() threw exception", ex);
        }

        if (callback != null) {
            Object postCqb = callback.patch_getCqb();
            Object postDlt = callback.patch_getDlt();
            if (savedCqb != null && postCqb == null) {
                callback.patch_setCqb(savedCqb);
                Logger.printDebug(() -> "releasePlayer: restored shared cqb");
            }
            if (savedDlt != null && postDlt == null) {
                callback.patch_setDlt(savedDlt);
                Logger.printDebug(() -> "releasePlayer: restored shared dlt");
            }
        }
    }

    private static void releaseAllFadingPlayers() {
        synchronized (fadingOutPlayers) {
            for (FadingPlayer fp : fadingOutPlayers) {
                try {
                    fp.player.patch_setVolume(0.0f);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Ignoring fp.player.patch_setVolume exception", ex);
                }
                releasePlayer(fp.player);
            }
            fadingOutPlayers.clear();
        }
        fadingLoopRunning = false;
    }

    /**
     * Emergency cleanup: releases all tracked players and resets state.
     * Used on errors and when crossfade is disabled/paused.
     */
    private static void cleanupAllPlayers() {
        releaseAllFadingPlayers();
        ExoPlayerAccess pi = pendingInPlayer;
        if (pi != null) {
            releasePlayer(pi);
            pendingInPlayer = null;
        }
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) {
            releasePlayer(po);
            pendingOutPlayer = null;
        }
        crossfadeInPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        currentFadeInVolume = 0.0f;
    }

    /**
     * Starts the independent fading loop if not already running.
     * The loop ticks all fade-out animations and releases players
     * when their volume reaches zero.
     */
    private static void ensureFadingLoopRunning() {
        if (fadingLoopRunning) return;
        if (fadingOutPlayers.isEmpty()) return;
        fadingLoopRunning = true;
        mainHandler.post(CrossfadeManager::tickFadingLoop);
    }

    private static void tickFadingLoop() {
        synchronized (fadingOutPlayers) {
            Iterator<FadingPlayer> it = fadingOutPlayers.iterator();
            while (it.hasNext()) {
                FadingPlayer fp = it.next();
                float vol = fp.currentVolume();
                int playerState = -1;
                try {
                    playerState = fp.player.patch_getPlaybackState();
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Ignoring fp.player.patch_getPlaybackState exception", ex);
                }
                final int playerStateFinal = playerState;

                try {
                    fp.player.patch_setVolume(Math.max(0.0f, vol));
                    long elapsed = System.currentTimeMillis() - fp.startTimeMs;
                    if (elapsed % 500 < TICK_MS) {
                        Logger.printDebug(() -> String.format(Locale.US,
                                "fade-out: @%d vol=%.2f state=%d elapsed=%dms",
                                System.identityHashCode(fp.player), vol, playerStateFinal, elapsed));
                    }
                } catch (Exception ex) {
                    Logger.printDebug(() -> "fade-out setVolume threw: " + ex.getMessage()
                            + " player=@" + System.identityHashCode(fp.player)
                            + " state=" + playerStateFinal);
                }

                if (fp.isComplete()) {
                    try {
                        fp.player.patch_setVolume(0.0f);
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "Ignoring fp.player.patch_setVolume exception", ex);
                    }
                    releasePlayer(fp.player);
                    it.remove();
                }
            }
        }

        if (!fadingOutPlayers.isEmpty()) {
            mainHandler.postDelayed(CrossfadeManager::tickFadingLoop, TICK_MS);
        } else {
            fadingLoopRunning = false;
            Logger.printDebug(() -> "Fading loop stopped — all fade-outs complete");
        }
    }

    /**
     * Injection point.
     */
    public static void onActivityStop() {
        if (!CROSSFADE_ENABLED) return;

        activityRunning = false;
        stopAutoAdvanceMonitor();
        if (crossfadeInProgress) {
            Logger.printDebug(() -> "onActivityStop: aborting crossfade");
            abortCrossfadeNow();
        }
    }

    /**
     * Injection point.
     */
    public static void onActivityStart() {
        if (!CROSSFADE_ENABLED) return;

        activityRunning = true;
        if (!sessionPaused.get()) {
            startAutoAdvanceMonitor();
        }
    }

    public static boolean isSessionPaused() {
        return sessionPaused.get();
    }

    @SuppressLint("MissingPermission")
    public static void toggleSessionPause() {
        boolean current;
        boolean isNowPaused;

        do {
            current = sessionPaused.get();
            isNowPaused = !current;
        } while (!sessionPaused.compareAndSet(current, isNowPaused));

        boolean finalIsNowPaused = isNowPaused;
        Logger.printDebug(() -> "Session " + (finalIsNowPaused ? "PAUSED" : "RESUMED")
                + " [inVideo=" + isCurrentlyInVideoMode()
                + " inProgress=" + crossfadeInProgress + "]");

        if (isNowPaused) {
            abortCrossfadeNow();
            stopAutoAdvanceMonitor();
        } else {
            startAutoAdvanceMonitor();
        }

        Context ctx = Utils.getContext();
        if (ctx != null) {
            try {
                Vibrator vib = (Vibrator) ctx.getSystemService(
                        Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    vib.vibrate(100);
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Ignoring vibration exception", ex);
            }

            Utils.showToastShort(isNowPaused
                    ? "Crossfade paused for this session"
                    : "Crossfade resumed");
        }
    }

    public static boolean isCrossfadeActive() {
        return !sessionPaused.get();
    }

    /**
     * Injection point.
     * Called by the bytecode hook on the audio/video toggle.
     * Blocks audio→video transitions when crossfade is active.
     * Video→audio transitions are always allowed.
     */
    public static boolean shouldBlockVideoToggle(Object nba) {
        if (!CROSSFADE_ENABLED) return false;

        lastNbaRef = new WeakReference<>(nba);
        if (internalToggle) return false;
        tryAttachLongPressHandler();
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            boolean isAudioMode = toggle.patch_isAudioMode();

            Logger.printDebug(() -> "videoToggle: isAudioMode=" + isAudioMode
                    + " paused=" + sessionPaused.get() + " inVideoMode(before)=" + inVideoMode);

            if (sessionPaused.get()) {
                if (!isAudioMode) {
                    manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
                }
                Logger.printDebug(() -> "videoToggle → ALLOW (crossfade inactive)");
                return false;
            }

            if (isAudioMode) {
                Logger.printDebug(() -> "videoToggle → BLOCK (audio→video while crossfade active)");
                Utils.showToastShort("Video mode is not available while crossfade is enabled");
                return true;
            }

            inVideoMode = false;
            manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
            Logger.printDebug(() -> "videoToggle → ALLOW (video→audio, suppressing crossfade for 500ms)");
            return false;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not check video toggle state", ex);
            return false;
        }
    }

    private static void forceAudioModeIfNeeded() {
        Object nba = lastNbaRef.get();
        if (nba == null) return;
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            if (!toggle.patch_isAudioMode()) {
                toggle.patch_forceAudioModeSilent();
                inVideoMode = false;
                audioModeWasForced = true;
                Logger.printDebug(() -> "Silently forced audio mode (no reactive broadcast to nmi)");
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not force audio mode", ex);
        }
    }

    private static void restoreVideoModeSilently() {
        Object nba = lastNbaRef.get();
        if (nba == null) return;
        try {
            ((VideoToggleAccess) nba).patch_restoreVideoModeSilent();
            inVideoMode = true;
            Logger.printDebug(() -> "Silently restored video mode preference (ready for next crossfade)");
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not restore video mode", ex);
        }
    }

    private static boolean isCurrentlyInVideoMode() {
        Object nba = lastNbaRef != null ? lastNbaRef.get() : null;
        if (nba != null) {
            try {
                VideoToggleAccess toggle = (VideoToggleAccess) nba;
                boolean isAudio = toggle.patch_isAudioMode();
                inVideoMode = !isAudio;
                return !isAudio;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not query live video mode", ex);
            }
        }
        return inVideoMode;
    }

    private static boolean isSessionControlEnabled() {
        return Settings.CROSSFADE_SESSION_CONTROL.get();
    }

    private static int getCrossfadeDurationMs() {
        return Settings.CROSSFADE_DURATION.get().milliseconds;
    }

    private static long getLongPressThresholdMs() {
        return 800;
    }

    private static final String[] SHUFFLE_IDS = {
            "queue_shuffle_button",
            "queue_shuffle",
            "playback_queue_shuffle_button_view",
            "overlay_queue_shuffle_button_view"
    };

    private static Runnable pendingLongPress;
    private static volatile boolean longPressHandled = false;

    private static void tryAttachLongPressHandler() {
        if (!isSessionControlEnabled()) return;

        boolean allAlive = !longPressRefs.isEmpty();
        for (WeakReference<View> ref : longPressRefs) {
            View v = ref.get();
            if (v == null || !v.isAttachedToWindow()) {
                allAlive = false;
                break;
            }
        }
        if (allAlive && !longPressRefs.isEmpty()) return;

        mainHandler.post(() -> {
            try {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.getWindow() == null) return;

                View decorView = activity.getWindow().getDecorView();
                Resources res = activity.getResources();
                String pkg = activity.getPackageName();

                List<View> allButtons = new ArrayList<>();
                for (String idName : SHUFFLE_IDS) {
                    @SuppressLint("DiscouragedApi")
                    int id = res.getIdentifier(idName, "id", pkg);
                    if (id == 0) {
                        Logger.printDebug(() -> "  shuffle id '" + idName + "' → not found in resources");
                        continue;
                    }
                    List<View> matched = new ArrayList<>();
                    findAllViewsById(decorView, id, matched);
                    for (View v : matched) {
                        Logger.printDebug(() -> "  shuffle id '" + idName + "' → "
                                + v.getClass().getSimpleName()
                                + " vis=" + v.getVisibility()
                                + " attached=" + v.isAttachedToWindow()
                                + " parent=" + (v.getParent() != null
                                    ? v.getParent().getClass().getSimpleName() : "null"));
                    }
                    allButtons.addAll(matched);
                }

                Logger.printDebug(() -> "Found " + allButtons.size()
                        + " shuffle button instances");

                longPressRefs.clear();

                for (View shuffleBtn : allButtons) {
                    attachTouchLongPress(shuffleBtn);
                    longPressRefs.add(new WeakReference<>(shuffleBtn));

                    View parent = (View) shuffleBtn.getParent();
                    if (parent != null && parent != decorView) {
                        attachTouchLongPress(parent);
                        longPressRefs.add(new WeakReference<>(parent));
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Long-press attach skipped", ex);
            }
        });
    }

    private static void findAllViewsById(View root, int id,
                                          List<View> out) {
        if (root.getId() == id) out.add(root);
        if (root instanceof ViewGroup vg) {
            for (int i = 0, childCount = vg.getChildCount(); i < childCount; i++) {
                findAllViewsById(vg.getChildAt(i), id, out);
            }
        }
    }

    private static void attachTouchLongPress(View btn) {
        final float[] downXY = new float[2];
        final boolean[] longPressTriggered = {false};

        btn.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downXY[0] = event.getRawX();
                    downXY[1] = event.getRawY();
                    longPressTriggered[0] = false;
                    longPressHandled = false;
                    if (pendingLongPress != null) {
                        mainHandler.removeCallbacks(pendingLongPress);
                    }
                    pendingLongPress = () -> {
                        if (longPressHandled) return;
                        longPressHandled = true;
                        longPressTriggered[0] = true;
                        toggleSessionPause();
                        Logger.printDebug(() -> "Shuffle long-press fired ("
                                                + getLongPressThresholdMs() + "ms)");
                    };
                    mainHandler.postDelayed(pendingLongPress,
                            getLongPressThresholdMs());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downXY[0];
                    float dy = event.getRawY() - downXY[1];
                    if (Math.sqrt(dx * dx + dy * dy) > 30) {
                        if (pendingLongPress != null) {
                            mainHandler.removeCallbacks(pendingLongPress);
                            pendingLongPress = null;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (pendingLongPress != null) {
                        mainHandler.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                    }
                    if (longPressTriggered[0]) {
                        return true;
                    }
                    v.performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (pendingLongPress != null) {
                        mainHandler.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                    }
                    return true;
            }
            return false;
        });
    }

}

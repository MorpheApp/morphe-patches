package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import android.annotation.SuppressLint;

import java.lang.ref.WeakReference;
import java.util.Iterator;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Player-swap crossfade manager for YouTube Music.
 *
 * Strategy: when a skip-next is detected (stopVideo reason=5), we
 * preserve the OLD ExoPlayer (which keeps playing the outgoing track)
 * and create a NEW ExoPlayer via YT Music's own factory method so it
 * has full DRM / DataSource configuration.  We swap the coordinator's
 * player to the new one so the subsequent loadVideo flow uses it.
 * Once the new track reaches STATE_READY we run a configurable
 * crossfade, then release the old player.
 *
 * Multi-player fade system: when a skip arrives during an active
 * crossfade, the current incoming player is "demoted" to a quick
 * fade-out, a fresh player is created for the next track, and the
 * native loadVideo naturally loads onto it.  Multiple fade-out
 * animations run concurrently via a dedicated fading loop, each
 * player releasing when its volume reaches zero.
 *
 * Each obfuscated YTM class is accessed through a dedicated interface
 * whose bridge methods are injected at patch time (same pattern as YT
 * VideoInformation).  Each interface maps 1-to-1 with an obfuscated
 * class so that when field/method names change between YTM versions,
 * only the affected interface's fingerprint and bridge methods need
 * updating.
 * @noinspection unused
 */
@SuppressLint({"MissingPermission", "PrivateApi", "DiscouragedApi"})
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

    // ------------------------------------------------------------------ //
    //  Interfaces — one per obfuscated class, bound at patch time         //
    // ------------------------------------------------------------------ //

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
        Object patch_getPlayerListener();
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
        void patch_addPlayerListener(Object listener);
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

    // ------------------------------------------------------------------ //
    //  Constants and fields                                                //
    // ------------------------------------------------------------------ //

    private static void logDebug(String msg) {
        Logger.printDebug(() -> msg);
    }

    private static void logInfo(String msg) {
        Logger.printInfo(() -> msg);
    }

    private static void logError(String msg) {
        Logger.printException(() -> msg);
    }

    private static void logError(String msg, Exception e) {
        Logger.printException(() -> msg, e);
    }

    private static void logWarn(String msg) {
        Logger.printInfo(() -> msg);
    }

    private static void logWarn(String msg, Exception e) {
        Logger.printInfo(() -> msg, e);
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
            switch (this) {
                case EASE_OUT_CUBIC: return 1.0f - t * t * t;
                case EASE_OUT_QUAD:  return (1.0f - t) * (1.0f - t);
                case SMOOTHSTEP:    return 1.0f - (3.0f * t * t - 2.0f * t * t * t);
                default:            return (float) Math.cos(t * Math.PI / 2.0);
            }
        }

        public float in(float t) {
            if (this == SMOOTHSTEP) return 3.0f * t * t - 2.0f * t * t * t;
            return (float) Math.sin(t * Math.PI / 2.0);
        }
    }

    private static volatile boolean sessionPaused = false;
    private static volatile boolean inVideoMode = false;
    private static volatile long manualToggleSuppressionUntil = 0;
    private static volatile boolean crossfadeInProgress = false;
    private static volatile boolean audioModeWasForced = false;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int TICK_MS = 50;
    private static final int READY_POLL_MS = 100;
    private static final int READY_TIMEOUT_MS = 10000;
    private static final int STATE_READY = 3;
    private static final int REASON_DIRECTOR_RESET = 5;
    private static final long AUTO_ADVANCE_THRESHOLD_MS = 5000;
    private static final long MONITOR_POLL_MS = 100;
    private static final int QUICK_FADE_MS = 400;

    private static volatile SharedCallbackAccess activeSharedCallback = null;
    private static volatile ExoPlayerAccess crossfadeInPlayer = null;
    private static volatile ExoPlayerAccess pendingInPlayer = null;
    private static volatile ExoPlayerAccess pendingOutPlayer = null;
    private static volatile PlayerCoordinatorAccess activeCoordinator = null;
    private static volatile float currentFadeInVolume = 0.0f;

    private static final java.util.List<FadingPlayer> fadingOutPlayers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static volatile boolean fadingLoopRunning = false;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static WeakReference<Object> lastNbaRef = new WeakReference<>(null);
    private static volatile boolean internalToggle = false;
    private static Runnable autoAdvanceMonitorRunnable = null;

    private static int playersCreated = 0;
    private static int playersReleased = 0;
    private static final java.util.List<WeakReference<View>> longPressRefs =
            new java.util.ArrayList<>();

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

    // ------------------------------------------------------------------ //
    //  Public hook: stopVideo (manual skip-next)                          //
    // ------------------------------------------------------------------ //

    private static int lastLoggedReason = -1;
    private static int suppressedReasonCount = 0;

    public static boolean onBeforeStopVideo(Object atadInstance, int reason) {
        lastAtadRef = new WeakReference<>(atadInstance);
        tryAttachLongPressHandler();

        if (crossfadeInProgress) {
            if (reason == REASON_DIRECTOR_RESET) {
                return handleChainedSkip(atadInstance);
            }
            logDebug("stopVideo(" + reason + "): BLOCKED — crossfade in progress");
            return true;
        }

        if (reason != REASON_DIRECTOR_RESET) {
            if (reason == lastLoggedReason) {
                suppressedReasonCount++;
            } else {
                if (suppressedReasonCount > 0) {
                    logDebug("  (suppressed " + suppressedReasonCount
                            + " duplicate reason=" + lastLoggedReason + " entries)");
                }
                logDebug("stopVideo reason=" + reason + " — not a skip, ignoring");
                lastLoggedReason = reason;
                suppressedReasonCount = 0;
            }
            return false;
        }
        lastLoggedReason = -1;
        suppressedReasonCount = 0;

        if (System.currentTimeMillis() < manualToggleSuppressionUntil) {
            logInfo("stopVideo(5): skip — within manual toggle suppression window");
            return false;
        }

        if (!isEnabled() || sessionPaused || getCrossfadeDurationMs() <= 0) {
            logDebug("stopVideo(5): skip [enabled=" + isEnabled()
                    + " paused=" + sessionPaused + " inVideo=" + isCurrentlyInVideoMode() + "]");
            return false;
        }

        if (isFromTaskRemoval()) {
            logDebug("stopVideo(5): skip — triggered by onTaskRemoved (activity killed)");
            return false;
        }

        try {
            PlayerCoordinatorAccess coordinator = getCoordinatorFromAtad(atadInstance);
            if (coordinator == null) {
                logError("Could not find coordinator from atad");
                return false;
            }

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) {
                logError("Coordinator ExoPlayer is null");
                return false;
            }

            boolean isAutoAdvance = false;
            try {
                long pos = currentExo.patch_getCurrentPosition();
                long duration = currentExo.patch_getDuration();
                long remaining = (duration > 0) ? duration - pos : Long.MAX_VALUE;
                isAutoAdvance = duration > 0 && remaining >= 0
                        && remaining < AUTO_ADVANCE_THRESHOLD_MS;
                logDebug("stopVideo(5): pos=" + pos + "ms dur=" + duration
                        + "ms remaining=" + remaining
                        + "ms → " + (isAutoAdvance ? "AUTO-ADVANCE" : "MANUAL SKIP"));
            } catch (Exception e) {
                logWarn("Could not read position/duration, assuming manual skip", e);
            }

            if (isAutoAdvance && !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
                logDebug("stopVideo(5): skip — auto-advance crossfade disabled");
                return false;
            }
            if (!isAutoAdvance && !Settings.CROSSFADE_ON_SKIP.get()) {
                logDebug("stopVideo(5): skip — manual skip crossfade disabled");
                return false;
            }

            boolean wasInVideoMode = isCurrentlyInVideoMode();

            logInfo("stopVideo(5): STARTING crossfade [enabled=" + isEnabled()
                    + " paused=" + sessionPaused
                    + " wasInVideo=" + wasInVideoMode + "]");

            int currentState = currentExo.patch_getPlaybackState();
            logDebug("Current player state=" + currentState
                    + " class=" + currentExo.getClass().getName());

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                logInfo("Silent audio mode set BEFORE factory (video→audio, no nmi broadcast)");
            }

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;

            coordinator.patch_setExoPlayer(newExo);
            logInfo("Swapped coordinator ExoPlayer → new player");

            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
                logDebug("Updated video surface → new player");
            }

            logInfo("Old player preserved (keeps playing), polling for new track ready"
                    + " — BLOCKING native stopVideo");
            pollForNewTrackReady(newExo);

            return true;

        } catch (Exception e) {
            logError("onBeforeStopVideo error", e);
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
        logInfo("stopVideo(5): CHAINED SKIP — creating new player, deferring demotion until READY");

        if (!isEnabled() || sessionPaused || getCrossfadeDurationMs() <= 0) {
            logDebug("Chained skip: crossfade now disabled/paused — aborting crossfade");
            abortCrossfadeNow();
            return false;
        }

        try {
            PlayerCoordinatorAccess coordinator = activeCoordinator;
            if (coordinator == null) {
                coordinator = getCoordinatorFromAtad(atadInstance);
                if (coordinator == null) {
                    logError("Chained skip: coordinator null — aborting");
                    abortCrossfadeNow();
                    return false;
                }
            }

            ExoPlayerAccess oldPending = pendingInPlayer;
            if (oldPending != null) {
                logInfo("Chained skip: releasing previous pending player @"
                        + System.identityHashCode(oldPending)
                        + " (never reached READY)");
                releasePlayer(oldPending);
            }

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                logError("Chained skip: factory failed — aborting crossfade");
                abortCrossfadeNow();
                return false;
            }

            newExo.patch_setVolume(0.0f);
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;

            coordinator.patch_setExoPlayer(newExo);
            logInfo("Chained skip: swapped coordinator → new player @"
                    + System.identityHashCode(newExo)
                    + " (current animation continues uninterrupted)");

            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
            }

            pollForNewTrackReady(newExo);

            return true;
        } catch (Exception e) {
            logError("handleChainedSkip error", e);
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
            if (session == null) { logError("createNewPlayer: session null"); return null; }

            PlayerFactoryAccess factory = (PlayerFactoryAccess) session.patch_getFactory();
            if (factory == null) { logError("createNewPlayer: factory null"); return null; }

            Object loadControl = coordinator.patch_getLoadControl();
            if (loadControl == null) { logError("createNewPlayer: loadControl null"); return null; }

            SharedStateAccess sharedState = (SharedStateAccess) coordinator.patch_getSharedState();
            if (sharedState == null) { logError("createNewPlayer: sharedState null"); return null; }

            SharedCallbackAccess sharedCallback =
                    (SharedCallbackAccess) coordinator.patch_getSharedCallback();
            if (sharedCallback == null) { logError("createNewPlayer: sharedCallback null"); return null; }
            activeSharedCallback = sharedCallback;

            Object oldTimeline = sharedState.patch_getTimeline();
            Object oldCqb = sharedCallback.patch_getCqb();
            logDebug("Pre-factory shared state: cqb=" + (oldCqb != null));
            sharedState.patch_setTimeline(null);
            sharedCallback.patch_setCqb(null);

            ExoPlayerAccess newExo = createPlayerViaFactory(factory, coordinator, loadControl);
            if (newExo == null) {
                logError("Factory returned null — restoring");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            Object postTimeline = sharedState.patch_getTimeline();
            Object postCqb = sharedCallback.patch_getCqb();
            logDebug("Post-factory shared state: cqb=" + (postCqb != null)
                    + " newExo=" + System.identityHashCode(newExo));
            if (postTimeline == null) {
                logError("Factory failed to set timeline — aborting");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }
            if (postCqb == null) {
                logError("Factory failed to set cqb — aborting");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            return newExo;
        } catch (Exception e) {
            logError("createNewPlayer error", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Public hook: playNextInQueue (gapless auto-advance)                //
    // ------------------------------------------------------------------ //

    public static void onBeforePlayNext(Object coordinatorInstance) {
        logInfo("onBeforePlayNext called");
        tryAttachLongPressHandler();

        if (!isEnabled() || sessionPaused || getCrossfadeDurationMs() <= 0
                || crossfadeInProgress) {
            return;
        }

        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            logDebug("PlayNext: skip — auto-advance crossfade disabled");
            return;
        }

        try {
            boolean wasInVideoMode = isCurrentlyInVideoMode();

            PlayerCoordinatorAccess coordinator =
                    (PlayerCoordinatorAccess) coordinatorInstance;

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) return;

            int currentState = currentExo.patch_getPlaybackState();
            logDebug("PlayNext: current player state=" + currentState
                    + " wasInVideo=" + wasInVideoMode);

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return;

            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;

            coordinator.patch_setExoPlayer(newExo);
            logInfo("PlayNext: swapped coordinator ExoPlayer → new player");

            VideoSurfaceAccess surface =
                    (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
                logDebug("PlayNext: updated video surface → new player");
            }

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                logInfo("PlayNext: forced audio mode for incoming track (was in video mode)");
            }

            logInfo("PlayNext: old player preserved, polling for new track ready");
            pollForNewTrackReady(newExo);

        } catch (Exception e) {
            logError("onBeforePlayNext error", e);
            cleanupAllPlayers();
            if (audioModeWasForced) {
                audioModeWasForced = false;
                restoreVideoModeSilently();
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Public hooks: pauseVideo / playVideo (MedialibPlayer layer)        //
    // ------------------------------------------------------------------ //

    private static long lastPauseEventMs = 0;
    private static long lastPlayEventMs = 0;
    private static final long EVENT_DEDUP_WINDOW_MS = 100;

    /**
     * Hooked at the top of MedialibPlayer.pauseVideo.
     * Returns true to BLOCK the pause, false to allow.
     */
    public static boolean onPauseVideo() {
        long now = System.currentTimeMillis();
        if (now - lastPauseEventMs < EVENT_DEDUP_WINDOW_MS) return false;
        lastPauseEventMs = now;

        if (!crossfadeInProgress) {
            return false;
        }

        logInfo("onPauseVideo during crossfade — aborting crossfade, allowing pause");
        abortCrossfadeNow();
        return false;
    }

    /**
     * Hooked at the top of MedialibPlayer.playVideo.
     */
    public static void onPlayVideo(Object atadInstance) {
        long now = System.currentTimeMillis();
        if (now - lastPlayEventMs < EVENT_DEDUP_WINDOW_MS) return;
        lastPlayEventMs = now;

        if (atadInstance != null) {
            lastAtadRef = new WeakReference<>(atadInstance);
        }

        logDebug("onPlayVideo [crossfading=" + crossfadeInProgress
                + ", atad=" + (atadInstance != null) + "]");
        if (!crossfadeInProgress) {
            startAutoAdvanceMonitor();
        }
    }

    // ------------------------------------------------------------------ //
    //  Poller: waits for new track to reach STATE_READY                   //
    // ------------------------------------------------------------------ //

    private static int lastPollState = -1;

    private static void pollForNewTrackReady(final ExoPlayerAccess newPlayer) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        lastPollState = -1;

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!crossfadeInProgress) return;
                if (newPlayer != pendingInPlayer) return;

                try {
                    int state = newPlayer.patch_getPlaybackState();
                    if (state == STATE_READY) {
                        logInfo("Pending track READY — promoting to crossfade");
                        onPendingPlayerReady(newPlayer);
                        return;
                    }

                    if (state == 4) {
                        logError("Pending player ENDED unexpectedly — aborting");
                        cleanupAllPlayers();
                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            restoreVideoModeSilently();
                        }
                        return;
                    }

                    if (state != lastPollState) {
                        logDebug("Poll: state → " + state);
                        lastPollState = state;
                    }

                    if (System.currentTimeMillis() > deadline) {
                        logError("Timeout waiting for new track");
                        cleanupAllPlayers();
                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            restoreVideoModeSilently();
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, READY_POLL_MS);
                } catch (Exception e) {
                    logError("Poll error", e);
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
            fadingOutPlayers.add(new FadingPlayer(outgoing, fadeDuration, curve));
            pendingOutPlayer = null;
            logInfo("Original outgoing player @" + System.identityHashCode(outgoing)
                    + " → fade-out list (full crossfade, " + fadeDuration + "ms)");
        }

        ExoPlayerAccess prevIncoming = crossfadeInPlayer;
        if (prevIncoming != null && prevIncoming != newPlayer) {
            float vol = currentFadeInVolume;
            long quickDuration = Math.max(200, (long) (QUICK_FADE_MS * vol));
            if (vol > 0.01f) {
                fadingOutPlayers.add(new FadingPlayer(prevIncoming, vol, quickDuration));
                logInfo("Previous incoming player @"
                        + System.identityHashCode(prevIncoming)
                        + " → quick fade-out from " + String.format("%.2f", vol)
                        + " over " + quickDuration + "ms");
            } else {
                releasePlayer(prevIncoming);
                logInfo("Previous incoming player @"
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

    // ------------------------------------------------------------------ //
    //  Auto-advance: position monitor & timed crossfade                   //
    // ------------------------------------------------------------------ //

    private static void startAutoAdvanceMonitor() {
        stopAutoAdvanceMonitor();
        if (!isEnabled() || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) return;

        autoAdvanceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isEnabled() || sessionPaused
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
                        logDebug("Auto-advance monitor: pos=" + pos
                                + "ms dur=" + dur + "ms remaining=" + remaining
                                + "ms trigger@" + fadeDuration + "ms");
                    }

                    if (dur <= fadeDuration) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    if (remaining <= fadeDuration && remaining > 0) {
                        logInfo("Auto-advance: triggering playNextInQueue"
                                + " at remaining=" + remaining
                                + "ms (fadeDuration=" + fadeDuration + "ms)");
                        stopAutoAdvanceMonitor();
                        try {
                            ((MedialibPlayerAccess) atad).patch_playNextInQueue();
                        } catch (Exception e) {
                            logWarn("playNextInQueue threw: " + e.getMessage());
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                } catch (Exception e) {
                    logWarn("Auto-advance monitor error", e);
                    mainHandler.postDelayed(this, MONITOR_POLL_MS * 2);
                }
            }
        };
        mainHandler.postDelayed(autoAdvanceMonitorRunnable, MONITOR_POLL_MS);
        logInfo("Auto-advance monitor started");
    }

    private static void stopAutoAdvanceMonitor() {
        if (autoAdvanceMonitorRunnable != null) {
            mainHandler.removeCallbacks(autoAdvanceMonitorRunnable);
            autoAdvanceMonitorRunnable = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Volume animation (configurable curve)                              //
    // ------------------------------------------------------------------ //

    private static void abortCrossfadeNow() {
        if (!crossfadeInProgress) return;

        ExoPlayerAccess inp = crossfadeInPlayer;
        ExoPlayerAccess pending = pendingInPlayer;
        ExoPlayerAccess pendOut = pendingOutPlayer;
        PlayerCoordinatorAccess coord = activeCoordinator;

        ExoPlayerAccess bestPlayer = null;
        boolean inpReady = false;
        if (inp != null) {
            try { inpReady = inp.patch_getPlaybackState() == STATE_READY; }
            catch (Exception ignored) {}
        }
        boolean pendingReady = false;
        if (pending != null) {
            try { pendingReady = pending.patch_getPlaybackState() == STATE_READY; }
            catch (Exception ignored) {}
        }

        if (pendingReady) {
            bestPlayer = pending;
        } else if (inpReady) {
            bestPlayer = inp;
        } else if (pendOut != null) {
            bestPlayer = pendOut;
        }

        if (bestPlayer != null && coord != null) {
            logInfo("abortCrossfadeNow: snapping to player @"
                    + System.identityHashCode(bestPlayer));
            try {
                bestPlayer.patch_setVolume(1.0f);
                bestPlayer.patch_setPlayWhenReady(true);
                coord.patch_setExoPlayer(bestPlayer);
                VideoSurfaceAccess surface =
                        (VideoSurfaceAccess) coord.patch_getVideoSurface();
                if (surface != null) surface.patch_setPlayerReference(bestPlayer);
            } catch (Exception e) {
                logWarn("abortCrossfadeNow: snap failed: " + e.getMessage());
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
        try { inPlayer.patch_setPlayWhenReady(true); } catch (Exception ignored) {}

        final long startTime = System.currentTimeMillis();
        final long duration = getCrossfadeDurationMs();

        logInfo("Crossfade fade-in started for @" + System.identityHashCode(inPlayer)
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
                        logDebug(String.format(
                                "fade-in: t=%.2f inVol=%.2f(st=%d) fadingOut=%d",
                                t, inVol, inState, fadingOutPlayers.size()));
                    }
                } catch (Exception e) {
                    logError("Fade-in tick error", e);
                }

                if (t < 1.0f) {
                    mainHandler.postDelayed(this, TICK_MS);
                } else {
                    logInfo("Fade-in complete for @" + System.identityHashCode(inPlayer));
                    inVideoMode = false;
                    currentFadeInVolume = 1.0f;
                    try { inPlayer.patch_setVolume(1.0f); } catch (Exception ignored) {}

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
                        logDebug("Fade-in complete but pending player exists — "
                                + "waiting for it to reach READY");
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Player creation via YTM factory                                    //
    // ------------------------------------------------------------------ //

    private static ExoPlayerAccess createPlayerViaFactory(
            PlayerFactoryAccess factory,
            PlayerCoordinatorAccess coordinator,
            Object loadControl) {
        try {
            Object player = factory.patch_createPlayer(coordinator, loadControl, 0);
            if (player != null) {
                playersCreated++;
                logInfo("Factory created player @"
                        + System.identityHashCode(player)
                        + " [created=" + playersCreated
                        + " released=" + playersReleased
                        + " outstanding="
                        + (playersCreated - playersReleased) + "]");
            }
            return (ExoPlayerAccess) player;
        } catch (Exception e) {
            logError("createPlayerViaFactory failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Stack trace utilities                                               //
    // ------------------------------------------------------------------ //

    private static boolean isFromTaskRemoval() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("onTaskRemoved".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    //  Coordinator traversal from atad                                    //
    // ------------------------------------------------------------------ //

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
        } catch (Exception e) {
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
                logError("atad player chain is null");
                return null;
            }

            int depth = 0;
            while (chain instanceof DelegateAccess) {
                Object delegate = ((DelegateAccess) chain).patch_getDelegate();
                if (delegate == null || delegate == chain) break;
                chain = delegate;
                depth++;
            }

            logDebug("Traversed " + depth + " delegates → "
                    + chain.getClass().getName());

            if (chain instanceof PlayerCoordinatorAccess) {
                return (PlayerCoordinatorAccess) chain;
            }

            logError("Innermost class is not a PlayerCoordinatorAccess: "
                    + chain.getClass().getName());
            return null;
        } catch (Exception e) {
            logError("getCoordinatorFromAtad error", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Player lifecycle — release and fading loop                         //
    // ------------------------------------------------------------------ //

    private static void releasePlayer(ExoPlayerAccess p) {
        if (p == null) return;

        playersReleased++;
        logInfo("releasePlayer: @" + System.identityHashCode(p)
                + " [created=" + playersCreated + " released=" + playersReleased
                + " outstanding=" + (playersCreated - playersReleased) + "]");

        SharedCallbackAccess callback = activeSharedCallback;
        Object savedCqb = null, savedDlt = null;
        if (callback != null) {
            savedCqb = callback.patch_getCqb();
            savedDlt = callback.patch_getDlt();
        }

        try { p.patch_setDltCallback(null); } catch (Exception ignored) {}

        try {
            p.patch_release();
        } catch (Exception e) {
            logDebug("releasePlayer: release() threw: " + e.getMessage());
        }

        if (callback != null) {
            Object postCqb = callback.patch_getCqb();
            Object postDlt = callback.patch_getDlt();
            if (savedCqb != null && postCqb == null) {
                callback.patch_setCqb(savedCqb);
                logDebug("releasePlayer: restored shared cqb");
            }
            if (savedDlt != null && postDlt == null) {
                callback.patch_setDlt(savedDlt);
                logDebug("releasePlayer: restored shared dlt");
            }
        }
    }

    private static void releaseAllFadingPlayers() {
        synchronized (fadingOutPlayers) {
            for (FadingPlayer fp : fadingOutPlayers) {
                try { fp.player.patch_setVolume(0.0f); } catch (Exception ignored) {}
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
        if (pi != null) { releasePlayer(pi); pendingInPlayer = null; }
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) { releasePlayer(po); pendingOutPlayer = null; }
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
                try { fp.player.patch_setVolume(Math.max(0.0f, vol)); }
                catch (Exception ignored) {}

                if (fp.isComplete()) {
                    try { fp.player.patch_setVolume(0.0f); } catch (Exception ignored) {}
                    releasePlayer(fp.player);
                    it.remove();
                }
            }
        }

        if (!fadingOutPlayers.isEmpty()) {
            mainHandler.postDelayed(CrossfadeManager::tickFadingLoop, TICK_MS);
        } else {
            fadingLoopRunning = false;
            logDebug("Fading loop stopped — all fade-outs complete");
        }
    }

    // ------------------------------------------------------------------ //
    //  Settings                                                           //
    // ------------------------------------------------------------------ //

    public static boolean isSessionPaused() {
        return sessionPaused;
    }

    public static void toggleSessionPause() {
        sessionPaused = !sessionPaused;
        logInfo("Session " + (sessionPaused ? "PAUSED" : "RESUMED")
                + " [inVideo=" + isCurrentlyInVideoMode()
                + " inProgress=" + crossfadeInProgress + "]");

        if (sessionPaused) {
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
            } catch (Exception ignored) {}

            String msg = sessionPaused
                    ? "Crossfade paused for this session"
                    : "Crossfade resumed";
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        }
    }

    public static boolean isCrossfadeActive() {
        return isEnabled() && !sessionPaused;
    }

    /**
     * Called by the bytecode hook on the audio/video toggle.
     * Blocks audio→video transitions when crossfade is active.
     * Video→audio transitions are always allowed.
     */
    public static boolean shouldBlockVideoToggle(Object nba) {
        lastNbaRef = new WeakReference<>(nba);
        if (internalToggle) return false;
        tryAttachLongPressHandler();
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            boolean isAudioMode = toggle.patch_isAudioMode();

            logInfo("videoToggle: isAudioMode=" + isAudioMode
                    + " enabled=" + isEnabled() + " paused=" + sessionPaused
                    + " inVideoMode(before)=" + inVideoMode);

            if (!isEnabled() || sessionPaused) {
                if (!isAudioMode) {
                    manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
                }
                logInfo("videoToggle → ALLOW (crossfade inactive)");
                return false;
            }

            if (isAudioMode) {
                logInfo("videoToggle → BLOCK (audio→video while crossfade active)");
                showVideoBlockedToast();
                return true;
            }

            inVideoMode = false;
            manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
            logInfo("videoToggle → ALLOW (video→audio, suppressing crossfade for 500ms)");
            return false;
        } catch (Exception e) {
            logWarn("Could not check video toggle state", e);
            return false;
        }
    }

    private static void showVideoBlockedToast() {
        try {
            Context ctx = Utils.getContext();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx,
                    "Video mode is not available while crossfade is enabled",
                    Toast.LENGTH_SHORT).show()
            );
        } catch (Exception ignored) {}
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
                logInfo("Silently forced audio mode (no reactive broadcast to nmi)");
            }
        } catch (Exception e) {
            logWarn("Could not force audio mode: " + e.getMessage());
        }
    }

    private static void restoreVideoModeSilently() {
        Object nba = lastNbaRef.get();
        if (nba == null) return;
        try {
            ((VideoToggleAccess) nba).patch_restoreVideoModeSilent();
            inVideoMode = true;
            logInfo("Silently restored video mode preference (ready for next crossfade)");
        } catch (Exception e) {
            logWarn("Could not restore video mode: " + e.getMessage());
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
            } catch (Exception e) {
                logDebug("Could not query live video mode: " + e.getMessage());
            }
        }
        return inVideoMode;
    }

    private static boolean isEnabled() {
        return Settings.CROSSFADE_ENABLED.get();
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

    // ------------------------------------------------------------------ //
    //  Long-press shuffle button to toggle crossfade session               //
    // ------------------------------------------------------------------ //

    private static final String[] SHUFFLE_IDS = {
            "queue_shuffle_button",
            "queue_shuffle",
            "playback_queue_shuffle_button_view",
            "overlay_queue_shuffle_button_view"
    };

    private static Runnable pendingLongPress;
    private static volatile boolean longPressHandled = false;

    private static void tryAttachLongPressHandler() {
        if (!isSessionControlEnabled() || !isEnabled()) return;

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

                java.util.List<View> allButtons = new java.util.ArrayList<>();
                for (String idName : SHUFFLE_IDS) {
                    int id = res.getIdentifier(idName, "id", pkg);
                    if (id == 0) {
                        logDebug("  shuffle id '" + idName + "' → not found in resources");
                        continue;
                    }
                    java.util.List<View> matched = new java.util.ArrayList<>();
                    findAllViewsById(decorView, id, matched);
                    for (View v : matched) {
                        logDebug("  shuffle id '" + idName + "' → "
                                + v.getClass().getSimpleName()
                                + " vis=" + v.getVisibility()
                                + " attached=" + v.isAttachedToWindow()
                                + " parent=" + (v.getParent() != null
                                    ? v.getParent().getClass().getSimpleName() : "null"));
                    }
                    allButtons.addAll(matched);
                }

                logDebug("Found " + allButtons.size()
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
            } catch (Exception e) {
                logDebug("Long-press attach skipped: " + e.getMessage());
            }
        });
    }

    private static void findAllViewsById(View root, int id,
                                          java.util.List<View> out) {
        if (root.getId() == id) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
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
                        logInfo("Shuffle long-press fired ("
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

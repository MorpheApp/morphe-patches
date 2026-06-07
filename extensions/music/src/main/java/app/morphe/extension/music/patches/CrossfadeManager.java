/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

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
        MILLISECONDS_10000(10_000),
        MILLISECONDS_11000(11_000),
        MILLISECONDS_12000(12_000);

        public final int milliseconds;

        CrossFadeDuration(int milliseconds) {
            this.milliseconds = milliseconds;
        }
    }

    public interface PlayerCoordinatorAccess {
        Object patch_getExoPlayer();
        void patch_setExoPlayer(Object player);
        void patch_setPlayerWithBindings(Object player);
        Object patch_getSession();
        Object patch_getLoadControl();
        Object patch_getSharedState();
        Object patch_getSharedCallback();
        Object patch_getVideoSurface();
        Object patch_getCoordinatorListener();
        void patch_playNextInQueueDirect();
    }

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
        void patch_addListener(Object listener);
        void patch_addDirectListener(Object listener);
        void patch_removeDirectListener(Object listener);
        void patch_detachCwhFromEventDispatch();
        int patch_getDirectListenerCount();
    }

    public interface SessionAccess {
        Object patch_getFactory();
    }

    public interface PlayerFactoryAccess {
        Object patch_createPlayer(Object coordinator, Object loadControl, int flags);
    }

    public interface SharedStateAccess {
        Object patch_getTimeline();
        void patch_setTimeline(Object timeline);
    }

    public interface SharedCallbackAccess {
        Object patch_getCqb();
        void patch_setCqb(Object cqb);
        Object patch_getDlt();
        void patch_setDlt(Object dlt);
    }

    public interface VideoSurfaceAccess {
        void patch_setPlayerReference(Object player);
    }

    public interface MedialibPlayerAccess {
        Object patch_getPlayerChain();
        void patch_playNextInQueue();
        void patch_forceStopVideo();
        void patch_forceLoadVideo();
    }

    public interface VideoToggleAccess {
        boolean patch_isAudioMode();
        void patch_forceAudioMode();
        void patch_triggerToggle();
        void patch_forceAudioModeSilent();
        void patch_restoreVideoModeSilent();
        void patch_restoreVideoMode();
    }

    public interface DelegateAccess {
        Object patch_getDelegate();
    }

    public interface ListenerWrapperAccess {
        Object patch_getWrappedListener();
    }

    private static void logDebug(Logger.LogMessage msg) {
        Logger.printDebug(msg);
    }

    private static void logInfo(Logger.LogMessage msg) {
        Logger.printInfo(msg);
    }

    private static void logWarn(Logger.LogMessage msg) {
        Logger.printInfo(msg);
    }

    private static void logWarn(Logger.LogMessage msg, Exception e) {
        Logger.printInfo(msg, e);
    }

    private static void logError(Logger.LogMessage msg) {
        Logger.printException(msg);
    }

    private static void logError(Logger.LogMessage msg, Exception e) {
        Logger.printException(msg, e);
    }

    private static String stopReasonName(int reason) {
        switch (reason) {
            case 1 -> {
                return "STOP(1)";
            }
            case 2 -> {
                return "PAUSE(2)";
            }
            case 3 -> {
                return "END_OF_CONTENT(3)";
            }
            case 4 -> {
                return "ERROR(4)";
            }
            case 5 -> {
                return "DIRECTOR_RESET/SKIP(5)";
            }
            case 6 -> {
                return "SEEK(6)";
            }
            case 7 -> {
                return "QUEUE_CHANGED(7)";
            }
            case 8 -> {
                return "PLAYLIST_CHANGED(8)";
            }
            case 9 -> {
                return "UNKNOWN_9(9)";
            }
            case 10 -> {
                return "UNKNOWN_10(10)";
            }
            case 11 -> {
                return "UNKNOWN_11(11)";
            }
            case 12 -> {
                return "RESET_INTERNALLY(12)";
            }
            default -> {
                return "UNKNOWN(" + reason + ")";
            }
        }
    }

    private static String dumpState() {
        return "STATE["
                + "inProgress=" + crossfadeInProgress
                + " autoAdv=" + autoAdvanceCrossfadeActive
                + " deferred=" + deferredSwapPending
                + " inPlayer=@" + System.identityHashCode(crossfadeInPlayer)
                + " pendIn=@" + System.identityHashCode(pendingInPlayer)
                + " pendOut=@" + System.identityHashCode(pendingOutPlayer)
                + " fadingOut=" + fadingOutPlayers.size()
                + " inVol=" + String.format(Locale.US, "%.2f", currentFadeInVolume)
                + " inVideo=" + inVideoMode
                + " nbaAlive=" + (lastNbaRef != null && lastNbaRef.get() != null)
                + " atadAlive=" + (lastAtadRef != null && lastAtadRef.get() != null)
                + " playing=" + playerIsPlaying
                + " created=" + playersCreated
                + " released=" + playersReleased
                + " outstanding=" + (playersCreated - playersReleased)
                + "]";
    }

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

    private static volatile boolean isCrossfadePaused = false;
    private static volatile boolean inVideoMode = false;
    private static volatile long manualToggleSuppressionUntil = 0;
    private static volatile boolean crossfadeInProgress = false;
    private static volatile boolean audioModeWasForced = false;
    private static volatile boolean activityRunning = false;
    private static volatile boolean playerIsPlaying = true;
    private static volatile boolean autoAdvanceCrossfadeActive = false;
    private static volatile boolean monitorCrossfadeActive = false;
    private static volatile boolean outgoingFadePreStarted = false;
    private static final boolean isCasting = false;
    public static final boolean is9x = VersionCheckPatch.IS_9_00_OR_GREATER;
    public static volatile boolean suppressCwhU = false;
    private static final boolean CROSSFADE_ENABLED = Settings.CROSSFADE_ENABLED.get();
    private static volatile boolean deferredSwapPending = false;
    private static Runnable deferredSwapRunnable = null;
    private static final long DEFERRED_SWAP_DELAY_MS = 500;
    private static volatile long deferredSwapStartTime = 0L;
    private static final long INTERNAL_CALL_WINDOW_MS = 100L;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int TICK_MS = 50;
    private static final long RELEASE_DRAIN_DELAY_MS = 150;
    private static final int READY_POLL_MS = 100;
    private static final int READY_TIMEOUT_MS = 10000;
    private static final int STATE_READY = 3;
    private static final int REASON_DIRECTOR_RESET = 5;
    private static final long AUTO_ADVANCE_THRESHOLD_MS = 5000;
    private static final long MONITOR_POLL_MS = 100;
    private static final long AUTO_ADVANCE_TRIGGER_BUFFER_MS = 300;
    private static final int QUICK_FADE_MS = 400;
    private static volatile SharedCallbackAccess activeSharedCallback = null;
    private static volatile ExoPlayerAccess crossfadeInPlayer = null;
    private static volatile ExoPlayerAccess pendingInPlayer = null;
    private static volatile ExoPlayerAccess pendingOutPlayer = null;
    private static volatile PlayerCoordinatorAccess activeCoordinator = null;
    private static volatile float currentFadeInVolume = 0.0f;
    private static volatile Object coordinatorListenerBxi = null;

    private static final List<FadingPlayer> fadingOutPlayers =
            Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean fadingLoopRunning = false;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static WeakReference<Object> lastNbaRef = new WeakReference<>(null);
    private static final boolean internalToggle = false;
    private static volatile boolean internalPlayNext = false;
    private static volatile boolean monitorTriggeredSkip = false;
    private static volatile boolean queueAdvancedByMonitor = false;
    private static Runnable autoAdvanceMonitorRunnable = null;

    private static int playersCreated = 0;
    private static int playersReleased = 0;

    private static class FadingPlayer {
        final ExoPlayerAccess player;
        final float startVolume;
        final long startTimeMs;
        final long fadeDurationMs;
        final FadeCurve curve;

        FadingPlayer(ExoPlayerAccess player, long fadeDurationMs, FadeCurve curve) {
            this.player = player;
            this.startVolume = 1.0f;
            this.startTimeMs = System.currentTimeMillis();
            this.fadeDurationMs = fadeDurationMs;
            this.curve = curve;
        }

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
    private static int lastAtadIdentity = 0;

    /**
     * Injection point.
     */
    public static boolean onBeforeStopVideo(Object atadInstance, int reason) {
        if (!CROSSFADE_ENABLED) return false;
        if (!crossfadeInProgress && isAudioRoutedToCast()) {
            logDebug(() -> "stopVideo(" + reason + "): skip — audio routed to cast/mirror (#1549)");
            return false;
        }

        int atadId = System.identityHashCode(atadInstance);
        if (atadId != lastAtadIdentity && lastAtadIdentity != 0) {
            logDebug(() -> "QUEUE-CHANGE DETECTED: atad identity changed @"
                    + lastAtadIdentity + " → @" + atadId
                    + " (new session/queue) " + dumpState());
        }
        lastAtadIdentity = atadId;
        lastAtadRef = new WeakReference<>(atadInstance);
        tryAttachLongPressHandler();

        if (crossfadeInProgress) {
            if (reason == REASON_DIRECTOR_RESET) {
                if (autoAdvanceCrossfadeActive) {
                    if (queueAdvancedByMonitor) {
                        logDebug(() -> "stopVideo(5): auto-advance + queue already advanced — BLOCKING natural-end");
                        return true;
                    }
                    return false;
                }
                return handleChainedSkip(atadInstance);
            }
            if (is9x) {
                logDebug(() -> "stopVideo/" + stopReasonName(reason) + ": ALLOW — 9.x native cycle (crossfade in progress)");
                return false;
            }
            logDebug(() -> "stopVideo/" + stopReasonName(reason) + ": BLOCKED — crossfade in progress");
            return true;
        }

        if (reason != REASON_DIRECTOR_RESET) {
            if (reason == lastLoggedReason) {
                suppressedReasonCount++;
            } else {
                if (suppressedReasonCount > 0) {
                    logDebug(() -> "  (suppressed " + suppressedReasonCount
                                        + " duplicate reason=" + lastLoggedReason + " entries)");
                }
                logDebug(() -> "stopVideo reason=" + reason + " — not a skip, ignoring");
                lastLoggedReason = reason;
                suppressedReasonCount = 0;
            }
            return false;
        }
        lastLoggedReason = -1;
        suppressedReasonCount = 0;

        if (System.currentTimeMillis() < manualToggleSuppressionUntil) {
            logDebug(() -> "stopVideo(5): skip — within manual toggle suppression window");
            return false;
        }

        if (isCrossfadePaused || getCrossfadeDurationMs() <= 0) {
            logDebug(() -> "stopVideo(5): skip [paused=" + isCrossfadePaused
                    + " inVideo=" + isCurrentlyInVideoMode() + "]");
            return false;
        }

        if (isFromTaskRemoval()) {
            logDebug(() -> "stopVideo(5): skip — triggered by onTaskRemoved (activity killed)");
            if (crossfadeInProgress) cleanupAllPlayers();
            return false;
        }

        try {
            PlayerCoordinatorAccess coordinator = getCoordinatorFromAtad(atadInstance);
            if (coordinator == null) {
                logError(() -> "Could not find coordinator from atad");
                return false;
            }

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) {
                logError(() -> "Coordinator ExoPlayer is null");
                return false;
            }

            boolean isAutoAdvance = queueAdvancedByMonitor;
            try {
                long pos = currentExo.patch_getCurrentPosition();
                long duration = currentExo.patch_getDuration();
                long remaining = (duration > 0) ? duration - pos : Long.MAX_VALUE;
                if (!isAutoAdvance) {
                    isAutoAdvance = duration > 0 && remaining >= 0
                            && remaining < AUTO_ADVANCE_THRESHOLD_MS;
                }
                final boolean isAutoAdvanceFinal = isAutoAdvance;
                logDebug(() -> "stopVideo(5): pos=" + pos + "ms dur=" + duration
                        + "ms remaining=" + remaining
                        + "ms queueAdvancedByMonitor=" + queueAdvancedByMonitor
                        + " → " + (isAutoAdvanceFinal ? "AUTO-ADVANCE" : "MANUAL SKIP"));
            } catch (Exception e) {
                final boolean isAutoAdvanceFinal = isAutoAdvance;
                logWarn(() -> "Could not read position/duration, assuming "
                        + (isAutoAdvanceFinal ? "auto-advance" : "manual skip"), e);
            }

            if (isAutoAdvance && !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
                logDebug(() -> "stopVideo(5): skip — auto-advance crossfade disabled");
                return false;
            }
            if (!isAutoAdvance && !Settings.CROSSFADE_ON_SKIP.get()) {
                logDebug(() -> "stopVideo(5): skip — manual skip crossfade disabled");
                return false;
            }

            if (is9x && isAutoAdvance && autoAdvanceCrossfadeActive) {
                logDebug(() -> "9.x: volume-fade auto-advance — allowing stopVideo(5) (outgoing fade running)");
                return false;
            }
            if (is9x && !isAutoAdvance && autoAdvanceCrossfadeActive) {
                autoAdvanceCrossfadeActive = false;
                queueAdvancedByMonitor = false;
                outgoingFadePreStarted = false;
                logDebug(() -> "9.x: manual skip aborted volume-fade — proceeding with normal crossfade");
            }

            boolean wasInVideoMode = isCurrentlyInVideoMode();

            logDebug(() -> "stopVideo(5): STARTING crossfade [paused=" + isCrossfadePaused
                        + " wasInVideo=" + wasInVideoMode
                        + " is9x=" + is9x + "]");

            int currentState = currentExo.patch_getPlaybackState();
            logDebug(() -> "Current player state=" + currentState
                    + " class=" + currentExo.getClass().getName());

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                logDebug(() -> "Silent audio mode set BEFORE factory (video→audio, no nmi broadcast)");
            }

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            newExo.patch_setVolume(0.0f);

            if (is9x) {
                pendingOutPlayer = currentExo;
                pendingInPlayer = newExo;
                activeCoordinator = coordinator;
                crossfadeInProgress = true;
                if (isAutoAdvance) {
                    autoAdvanceCrossfadeActive = true;
                    logDebug(() -> "9.x: auto-advance crossfade → autoAdvanceCrossfadeActive=true");

                    try {
                        currentExo.patch_detachCwhFromEventDispatch();
                        logDebug(() -> "9.x auto-advance: detached cwh from OUTGOING @"
                                + System.identityHashCode(currentExo) + " at swap time");
                    } catch (Exception e) {
                        logWarn(()-> "9.x auto-advance: cwh detach on outgoing failed: " + e.getMessage());
                    }
                }
                deferredSwapStartTime = System.currentTimeMillis();

                Object coordListener = null;
                try {
                    coordListener = coordinator.patch_getCoordinatorListener();
                    if (coordListener != null) {
                        currentExo.patch_removeDirectListener(coordListener);
                        logDebug(() -> "9.x: pre-removed coord listener from outgoing @"
                                + System.identityHashCode(currentExo));
                    }
                } catch (Exception e) {
                    logWarn(()-> "9.x: pre-remove coord listener failed: " + e.getMessage());
                }

                coordinator.patch_setPlayerWithBindings(newExo);
                logDebug(() -> "9.x: swapped coordinator → new player @" + System.identityHashCode(newExo)
                        + " via patch_setPlayerWithBindings (Lcou backref updated)");

                if (coordListener != null) {
                    try {
                        newExo.patch_addDirectListener(coordListener);
                    } catch (Exception e) {
                        logWarn(()-> "9.x: re-register coord listener failed: " + e.getMessage());
                    }
                }
                VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
                if (surface != null) {
                    surface.patch_setPlayerReference(newExo);
                }

                boolean outgoingWasPlaying = false;
                try {
                    long msSincePause = System.currentTimeMillis() - lastPauseVideoMs;
                    outgoingWasPlaying = playerIsPlaying
                            || msSincePause < PAUSE_TO_STOP_INTERNAL_WINDOW_MS;
                    final boolean outgoingWasPlayingFinal = outgoingWasPlaying;
                    logDebug(() -> "9.x: outgoing player re-enable check: playerIsPlaying=" + playerIsPlaying
                                        + " msSincePause=" + msSincePause + "ms → wasPlaying=" + outgoingWasPlayingFinal);
                    if (outgoingWasPlaying) {
                        currentExo.patch_setPlayWhenReady(true);
                        currentExo.patch_setVolume(1.0f);
                        logDebug(() -> "9.x: re-enabled outgoing player @" + System.identityHashCode(currentExo));
                    } else {
                        currentExo.patch_setVolume(0.0f);
                        logDebug(() -> "9.x: outgoing player was paused (genuine) — keeping silent @"
                                + System.identityHashCode(currentExo));
                    }
                } catch (Exception e) {
                    logWarn(()-> "9.x: could not configure outgoing player: " + e.getMessage());
                }

                if (isAutoAdvance && outgoingWasPlaying) {
                    FadeCurve outCurve = Settings.CROSSFADE_CURVE.get();
                    long outFadeDuration = getCrossfadeDurationMs();
                    fadingOutPlayers.add(new FadingPlayer(currentExo, outFadeDuration, outCurve));
                    outgoingFadePreStarted = true;
                    ensureFadingLoopRunning();
                    logDebug(() -> "9.x auto-advance: pre-started outgoing fade-out @"
                            + System.identityHashCode(currentExo)
                            + " over " + outFadeDuration + "ms");
                }

                pollForNewTrackReady(newExo);
                return false;
            } else {
                pendingOutPlayer = currentExo;
                pendingInPlayer = newExo;
                activeCoordinator = coordinator;
                crossfadeInProgress = true;
                if (isAutoAdvance) {
                    autoAdvanceCrossfadeActive = true;
                    logDebug(() -> "8.x: auto-advance crossfade → autoAdvanceCrossfadeActive=true");
                }

                coordinator.patch_setExoPlayer(newExo);
                logDebug(() -> "Swapped coordinator ExoPlayer → new player");

                VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
                if (surface != null) {
                    surface.patch_setPlayerReference(newExo);
                    logDebug(() -> "Updated video surface → new player");
                }

                logDebug(() -> "Old player preserved (keeps playing), polling for new track ready"
                                + " — BLOCKING native stopVideo");
                pollForNewTrackReady(newExo);

                return true;
            }

        } catch (Exception e) {
            logError(()-> "onBeforeStopVideo error", e);
            cleanupAllPlayers();
            if (audioModeWasForced) {
                audioModeWasForced = false;
                restoreVideoModeSilently();
            }
            return false;
        }
    }

    private static boolean handleChainedSkip(Object atadInstance) {
        if (!activityRunning) {
            logInfo(() -> "CHAINED SKIP suppressed — activity not running (likely teardown)");
            return false;
        }
        logDebug(() -> "stopVideo(5): CHAINED SKIP — creating new player, deferring demotion until READY");

        if (is9x) {
            long elapsed = System.currentTimeMillis() - deferredSwapStartTime;
            if (elapsed < INTERNAL_CALL_WINDOW_MS) {
                logDebug(() -> "9.x: internal second stopVideo(5) after " + elapsed
                                + "ms — allowing through");
                return false;
            }
        }

        if (isCrossfadePaused || getCrossfadeDurationMs() <= 0) {
            logDebug(() -> "Chained skip: crossfade now paused — aborting crossfade");
            abortCrossfadeNow();
            return false;
        }

        try {
            PlayerCoordinatorAccess coordinator = activeCoordinator;
            if (coordinator == null) {
                coordinator = getCoordinatorFromAtad(atadInstance);
                if (coordinator == null) {
                    logError(() -> "Chained skip: coordinator null — aborting");
                    abortCrossfadeNow();
                    return false;
                }
            }

            ExoPlayerAccess oldPending = pendingInPlayer;
            pendingInPlayer = null;

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                logError(() -> "Chained skip: factory failed — aborting crossfade");
                if (oldPending != null) {
                    if (is9x) detachPlayerListeners(oldPending);
                    releasePlayer(oldPending);
                }
                abortCrossfadeNow();
                return false;
            }

            newExo.patch_setVolume(0.0f);
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;

            Object chainedCoordListener = null;
            if (is9x) {
                try {
                    chainedCoordListener = coordinator.patch_getCoordinatorListener();
                    if (chainedCoordListener != null && oldPending != null) {
                        oldPending.patch_removeDirectListener(chainedCoordListener);
                        logDebug(() -> "9.x chained: pre-removed coord listener from @"
                                + System.identityHashCode(oldPending));
                    }
                } catch (Exception e) {
                    logWarn(()-> "9.x chained: pre-remove coord listener failed: " + e.getMessage());
                }
            }

            coordinator.patch_setPlayerWithBindings(newExo);
            logDebug(() -> "Chained skip: swapped coordinator → new player @"
                    + System.identityHashCode(newExo));

            if (is9x && chainedCoordListener != null) {
                try {
                    newExo.patch_addDirectListener(chainedCoordListener);
                } catch (Exception e) {
                    logWarn(()-> "9.x chained: re-register coord listener failed: " + e.getMessage());
                }
            }
            if (oldPending != null) {
                logDebug(() -> "Chained skip: releasing old pending @"
                        + System.identityHashCode(oldPending)
                        + " (never reached READY)");
                if (is9x) detachPlayerListeners(oldPending);
                releasePlayer(oldPending);
            }

            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
            }

            pollForNewTrackReady(newExo);

            return !is9x;
        } catch (Exception e) {
            logError(()-> "handleChainedSkip error", e);
            abortCrossfadeNow();
            return false;
        }
    }

    private static ExoPlayerAccess createNewPlayer(PlayerCoordinatorAccess coordinator) {
        try {
            SessionAccess session = (SessionAccess) coordinator.patch_getSession();
            if (session == null) {
                logError(() -> "createNewPlayer: session null");
                return null; }

            PlayerFactoryAccess factory = (PlayerFactoryAccess) session.patch_getFactory();
            if (factory == null) {
                logError(() -> "createNewPlayer: factory null");
                return null; }

            Object loadControl = coordinator.patch_getLoadControl();
            if (loadControl == null) {
                logError(() -> "createNewPlayer: loadControl null");
                return null; }

            SharedStateAccess sharedState = (SharedStateAccess) coordinator.patch_getSharedState();
            if (sharedState == null) {
                logError(() -> "createNewPlayer: sharedState null");
                return null; }

            SharedCallbackAccess sharedCallback =
                    (SharedCallbackAccess) coordinator.patch_getSharedCallback();
            if (sharedCallback == null) {
                logError(() -> "createNewPlayer: sharedCallback null");
                return null; }
            activeSharedCallback = sharedCallback;

            Object oldTimeline = sharedState.patch_getTimeline();
            Object oldCqb = sharedCallback.patch_getCqb();
            logDebug(() -> "Pre-factory shared state: cqb=" + (oldCqb != null));
            sharedState.patch_setTimeline(null);
            sharedCallback.patch_setCqb(null);

            ExoPlayerAccess newExo = createPlayerViaFactory(factory, coordinator, loadControl);
            if (newExo == null) {
                logError(() -> "Factory returned null — restoring");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            Object postTimeline = sharedState.patch_getTimeline();
            Object postCqb = sharedCallback.patch_getCqb();
            logDebug(() -> "Post-factory shared state: cqb=" + (postCqb != null)
                    + " newExo=" + System.identityHashCode(newExo));
            if (postTimeline == null) {
                if (!is9x) {
                    logError(() -> "Factory failed to set timeline — aborting");
                    sharedState.patch_setTimeline(oldTimeline);
                    sharedCallback.patch_setCqb(oldCqb);
                    return null;
                }
                logWarn(()-> "Factory did not re-set timeline (expected on 9.x — field is final, restoring)");
                sharedState.patch_setTimeline(oldTimeline);
            }
            if (postCqb == null) {
                logError(() -> "Factory failed to set cqb — aborting");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            return newExo;
        } catch (Exception e) {
            logError(()-> "createNewPlayer error", e);
            return null;
        }
    }

    /**
     * Injection point.
     */
    public static boolean onBeforePlayNext(Object coordinatorInstance) {
        if (!CROSSFADE_ENABLED) return false;

        if (!crossfadeInProgress && isAudioRoutedToCast()) {
            logDebug(() -> "playNext: skip — audio routed to cast/mirror (#1549)");
            return false;
        }

        if (monitorTriggeredSkip) {
            monitorTriggeredSkip = false;
            logDebug(() -> "PlayNext: monitor-triggered — allowing native auih.y()V (stopVideo intercepted by onBeforeStopVideo)");
            return false;
        }

        if (internalPlayNext) {
            internalPlayNext = false;
            return false;
        }

        logDebug(() -> "onBeforePlayNext called [crossfading=" + crossfadeInProgress
                + " autoAdvance=" + autoAdvanceCrossfadeActive + "]");
        tryAttachLongPressHandler();

        if (isCrossfadePaused || getCrossfadeDurationMs() <= 0) {
            return false;
        }
        if (crossfadeInProgress) {
            if (autoAdvanceCrossfadeActive) {
                logDebug(() -> "PlayNext: auto-advance crossfade in progress — blocking duplicate native call");
                return true;
            }
            return false;
        }

        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            logDebug(() -> "PlayNext: skip — auto-advance crossfade disabled");
            return false;
        }

        try {
            boolean wasInVideoMode = isCurrentlyInVideoMode();

            PlayerCoordinatorAccess coordinator =
                    (PlayerCoordinatorAccess) coordinatorInstance;

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) return false;

            int currentState = currentExo.patch_getPlaybackState();
            logDebug(() -> "PlayNext: current player state=" + currentState
                        + " wasInVideo=" + wasInVideoMode);

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;
            autoAdvanceCrossfadeActive = true;
            deferredSwapStartTime = System.currentTimeMillis(); // gate 9.x internal stopVideo(5)

            Object playNextCoordListener = null;
            if (is9x) {
                try {
                    playNextCoordListener = coordinator.patch_getCoordinatorListener();
                    if (playNextCoordListener != null) {
                        currentExo.patch_removeDirectListener(playNextCoordListener);
                        logDebug(() -> "9.x PlayNext: pre-removed coord listener from @"
                                + System.identityHashCode(currentExo));
                    }
                } catch (Exception e) {
                    logWarn(()-> "9.x PlayNext: pre-remove coord listener failed: " + e.getMessage());
                }
            }
            coordinator.patch_setPlayerWithBindings(newExo);
            logDebug(() -> "PlayNext: swapped coordinator → new player @"
                    + System.identityHashCode(newExo));

            if (is9x && playNextCoordListener != null) {
                try {
                    newExo.patch_addDirectListener(playNextCoordListener);
                } catch (Exception e) {
                    logWarn(()-> "9.x PlayNext: re-register coord listener failed: " + e.getMessage());
                }
            }
            VideoSurfaceAccess surface =
                    (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
                logDebug(() -> "PlayNext: updated video surface → new player");
            }

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                logDebug(() -> "PlayNext: forced audio mode for incoming track (was in video mode)");
            }

            internalPlayNext = true;
            Object atad = lastAtadRef.get();
            if (atad instanceof MedialibPlayerAccess) {
                try {
                    ((MedialibPlayerAccess) atad).patch_playNextInQueue();
                } catch (Exception e) {
                    logWarn(()-> "PlayNext: re-invoke threw: " + e.getMessage());
                } finally {
                    internalPlayNext = false;
                }
                try {
                    newExo.patch_setVolume(0.0f);
                    logDebug(() -> "PlayNext: volume re-enforced to 0 after native");
                } catch (Exception ignored) {}
            } else {
                internalPlayNext = false;
                logWarn(()-> "PlayNext: atad ref lost — cannot re-invoke native");
            }

            logDebug(() -> "PlayNext: old player preserved, polling for new track ready");
            pollForNewTrackReady(newExo);
            return true;

        } catch (Exception e) {
            logError(()-> "onBeforePlayNext error", e);
            cleanupAllPlayers();
            if (audioModeWasForced) {
                audioModeWasForced = false;
                restoreVideoModeSilently();
            }
            return false;
        }
    }

    /**
     * Injection point.
     */
    public static void onBeforeLoadVideo(Object newAtzqInstance) {
        if (!is9x) return;
        logDebug(() -> "9.x: onBeforeLoadVideo atzq=@" + System.identityHashCode(newAtzqInstance)
                + " crossfadeInProgress=" + crossfadeInProgress
                + " autoAdvActive=" + autoAdvanceCrossfadeActive);
    }

    private static long lastPauseEventMs = 0;
    private static long lastPlayEventMs = 0;
    private static final long EVENT_DEDUP_WINDOW_MS = 100;
    private static volatile long lastPauseVideoMs = System.currentTimeMillis();
    private static final long PAUSE_TO_STOP_INTERNAL_WINDOW_MS = 500;

    /**
     * Injection point.
     */
    public static void onPauseVideo() {
        if (!CROSSFADE_ENABLED) return;

        playerIsPlaying = false;
        long now = System.currentTimeMillis();
        if (now - lastPauseEventMs < EVENT_DEDUP_WINDOW_MS) return;
        lastPauseEventMs = now;

        lastPauseVideoMs = now;
        logDebug(() -> "onPauseVideo [crossfading=" + crossfadeInProgress + " autoAdv=" + autoAdvanceCrossfadeActive + "]");

        if (!crossfadeInProgress) {
            return;
        }

        logDebug(() -> "onPauseVideo: aborting crossfade " + dumpState());
        abortCrossfadeNow();
    }

    /**
     * Injection point.
     */
    public static void onPlayVideo(Object atadInstance) {
        if (!CROSSFADE_ENABLED) return;

        playerIsPlaying = true;
        long now = System.currentTimeMillis();
        if (now - lastPlayEventMs < EVENT_DEDUP_WINDOW_MS) return;
        lastPlayEventMs = now;

        if (atadInstance != null) {
            lastAtadRef = new WeakReference<>(atadInstance);
        }

        logDebug(() -> "onPlayVideo [crossfading=" + crossfadeInProgress
                + " deferred=" + deferredSwapPending
                + " atad=" + (atadInstance != null)
                + " nbaAlive=" + (lastNbaRef != null && lastNbaRef.get() != null) + "]");

        if (!isCrossfadePaused && isCurrentlyInVideoMode()) {
            logDebug(() -> "onPlayVideo: coercing video → audio (crossfade active)");
            forceAudioModeIfNeeded();
        }

        if (!crossfadeInProgress) {
            logDebug(() -> "onPlayVideo: starting auto-advance monitor");
            startAutoAdvanceMonitor();
        } else {
            logDebug(() -> "onPlayVideo: crossfade in progress — skipping auto-advance monitor start");
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

                try { newPlayer.patch_setVolume(0.0f); } catch (Exception ignored) {}

                try {
                    int state = newPlayer.patch_getPlaybackState();
                    if (state == STATE_READY) {
                        logDebug(() -> "Pending track READY — promoting to crossfade");
                        onPendingPlayerReady(newPlayer);
                        return;
                    }

                    if (state == 4) {
                        logError(() -> "Pending player ENDED unexpectedly — aborting");
                        cleanupAllPlayers();
                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            restoreVideoModeSilently();
                        }
                        return;
                    }

                    if (state != lastPollState) {
                        logDebug(() -> "Poll: state → " + state);
                        lastPollState = state;
                    }

                    if (System.currentTimeMillis() > deadline) {
                        logError(() -> "Timeout waiting for new track");
                        cleanupAllPlayers();
                        if (audioModeWasForced) {
                            audioModeWasForced = false;
                            restoreVideoModeSilently();
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, READY_POLL_MS);
                } catch (Exception e) {
                    logError(()-> "Poll error", e);
                    cleanupAllPlayers();
                    if (audioModeWasForced) {
                        audioModeWasForced = false;
                        restoreVideoModeSilently();
                    }
                }
            }
        }, READY_POLL_MS);
    }

    private static void onPendingPlayerReady(ExoPlayerAccess newPlayer) {
        FadeCurve curve = Settings.CROSSFADE_CURVE.get();
        long fadeDuration = getCrossfadeDurationMs();

        boolean trackAlreadyEnded = false;
        ExoPlayerAccess outgoing = pendingOutPlayer;
        if (outgoing != null) {
            if (is9x) {
                logDebug(() -> "onPendingPlayerReady (9.x): coordinator listener already migrated at start");
            }

            if (outgoingFadePreStarted) {
                logDebug(() -> "onPendingPlayerReady: outgoing @" + System.identityHashCode(outgoing)
                        + " fade-out already in flight (pre-started at swap time)");
                pendingOutPlayer = null;
                outgoingFadePreStarted = false;
            } else {
                long fadeOutDuration = fadeDuration;
                try {
                    long pos = outgoing.patch_getCurrentPosition();
                    long dur = outgoing.patch_getDuration();
                    if (dur > 0 && pos >= 0) {
                        long actualRemaining = dur - pos;
                        logDebug(() -> "onPendingPlayerReady: outgoing remaining=" + actualRemaining
                                                + "ms fadeDuration=" + fadeDuration + "ms");
                        if (actualRemaining <= 0) {
                            trackAlreadyEnded = true;
                            logDebug(() -> "Outgoing track ended before READY — "
                                                        + "releasing silently, fade-in only (no overlap possible)");
                        } else if (actualRemaining < fadeDuration) {
                            fadeOutDuration = Math.max(150, actualRemaining);
                            final long fadeOutDurationFinal = fadeOutDuration;
                            logDebug(() -> "Fade-out shortened to " + fadeOutDurationFinal
                                    + "ms to match remaining audio (was " + fadeDuration + "ms)");
                        }
                    }
                } catch (Exception e) {
                    logDebug(() -> "Could not read outgoing remaining time: " + e.getMessage());
                }
                pendingOutPlayer = null;
                if (trackAlreadyEnded) {
                    releasePlayer(outgoing);
                    logDebug(() -> "Original outgoing player @" + System.identityHashCode(outgoing)
                            + " → released (track ended before READY)");
                } else {
                    fadingOutPlayers.add(new FadingPlayer(outgoing, fadeOutDuration, curve));
                    final long fadeOutDurationFinal = fadeOutDuration;
                    logDebug(() -> "Original outgoing player @" + System.identityHashCode(outgoing)
                            + " → fade-out list (" + fadeOutDurationFinal + "ms)");
                }
            }
        }

        ExoPlayerAccess prevIncoming = crossfadeInPlayer;
        if (prevIncoming != null && prevIncoming != newPlayer) {
            float vol = currentFadeInVolume;
            long quickDuration = Math.max(200, (long) (QUICK_FADE_MS * vol));
            if (vol > 0.01f) {
                fadingOutPlayers.add(new FadingPlayer(prevIncoming, vol, quickDuration));
                logDebug(() -> "Previous incoming player @"
                        + System.identityHashCode(prevIncoming)
                        + " → quick fade-out from " + String.format(Locale.US, "%.2f", vol)
                        + " over " + quickDuration + "ms");
            } else {
                releasePlayer(prevIncoming);
                logDebug(() -> "Previous incoming player @"
                        + System.identityHashCode(prevIncoming)
                        + " → released (vol ≈ 0)");
            }
        }

        crossfadeInPlayer = newPlayer;
        pendingInPlayer = null;
        currentFadeInVolume = 0.0f;

        ensureFadingLoopRunning();
        animateCrossfade(newPlayer, trackAlreadyEnded ? QUICK_FADE_MS : 0);
    }

    private static void startAutoAdvanceMonitor() {
        stopAutoAdvanceMonitor();
        if (!isEnabled() || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            logDebug(() -> "startAutoAdvanceMonitor: skipped [enabled=" + isEnabled()
                    + " onAutoAdvance=" + Settings.CROSSFADE_ON_AUTO_ADVANCE.get() + "]");
            return;
        }
        if (autoAdvanceCrossfadeActive) {
            logDebug(() -> "startAutoAdvanceMonitor: skipped — 9.x volume-fade in progress");
            return;
        }

        autoAdvanceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isEnabled() || isCrossfadePaused
                        || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()
                        || crossfadeInProgress
                        || autoAdvanceCrossfadeActive) {
                    return;
                }

                if (isAudioRoutedToCast()) {
                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
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
                        logDebug(() -> "Auto-advance monitor: pos=" + pos
                                                + "ms dur=" + dur + "ms remaining=" + remaining
                                                + "ms trigger@" + (fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS) + "ms");
                    }

                    if (dur <= fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    if (remaining <= fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS && remaining > 0) {
                        logDebug(() -> "Auto-advance: monitor trigger at remaining=" + remaining
                                                + "ms (fadeDuration=" + fadeDuration + "ms)");
                        stopAutoAdvanceMonitor();

                        logDebug(() -> "Auto-advance: TRIGGER FIRED is9x=" + is9x
                                + " outgoing=@" + System.identityHashCode(exo)
                                + " coordExo=@" + System.identityHashCode(coordinator.patch_getExoPlayer())
                                + " fadeDur=" + fadeDuration + "ms state=" + state
                                + " pos=" + pos + " dur=" + dur);
                        monitorTriggeredSkip = true;
                        queueAdvancedByMonitor = true;
                        Context ctx = Utils.getContext();
                        if (ctx == null) {
                            monitorTriggeredSkip = false;
                            queueAdvancedByMonitor = false;
                            logWarn(()-> "Auto-advance: no Context — cannot dispatch MEDIA_NEXT");
                            return;
                        }
                        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                        if (am == null) {
                            monitorTriggeredSkip = false;
                            queueAdvancedByMonitor = false;
                            logWarn(()-> "Auto-advance: no AudioManager — cannot dispatch MEDIA_NEXT");
                            return;
                        }
                        try {
                            long evTime = SystemClock.uptimeMillis();
                            KeyEvent down = new KeyEvent(evTime, evTime,
                                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0);
                            KeyEvent up = new KeyEvent(evTime, evTime,
                                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0);
                            am.dispatchMediaKeyEvent(down);
                            am.dispatchMediaKeyEvent(up);
                            logDebug(() -> "Auto-advance: dispatched MEDIA_NEXT key event");
                        } catch (Exception e) {
                            monitorTriggeredSkip = false;
                            queueAdvancedByMonitor = false;
                            logWarn(()-> "Auto-advance: dispatchMediaKeyEvent failed: " + e.getMessage());
                        }
                        return;
                    }

                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                } catch (Exception e) {
                    logWarn(()-> "Auto-advance monitor error", e);
                    mainHandler.postDelayed(this, MONITOR_POLL_MS * 2);
                }
            }
        };
        mainHandler.postDelayed(autoAdvanceMonitorRunnable, MONITOR_POLL_MS);
        logDebug(() -> "Auto-advance monitor started");
    }

    private static void stopAutoAdvanceMonitor() {
        if (autoAdvanceMonitorRunnable != null) {
            mainHandler.removeCallbacks(autoAdvanceMonitorRunnable);
            autoAdvanceMonitorRunnable = null;
        }
    }

    private static void abortCrossfadeNow() {
        if (!crossfadeInProgress) return;
        logDebug(() -> "ABORT: " + dumpState());

        ExoPlayerAccess inp = crossfadeInPlayer;
        ExoPlayerAccess pending = pendingInPlayer;
        ExoPlayerAccess pendOut = pendingOutPlayer;
        PlayerCoordinatorAccess coord = activeCoordinator;

        ExoPlayerAccess bestPlayer;
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
        } else bestPlayer = pendOut;

        if (bestPlayer != null && coord != null) {
            logDebug(() -> "abortCrossfadeNow: snapping to player @"
                    + System.identityHashCode(bestPlayer));
            try {
                bestPlayer.patch_setVolume(1.0f);
                bestPlayer.patch_setPlayWhenReady(true);
                coord.patch_setExoPlayer(bestPlayer);
                VideoSurfaceAccess surface =
                        (VideoSurfaceAccess) coord.patch_getVideoSurface();
                if (surface != null) surface.patch_setPlayerReference(bestPlayer);
            } catch (Exception e) {
                logWarn(()-> "abortCrossfadeNow: snap failed: " + e.getMessage());
            }
        }

        if (inp != null && inp != bestPlayer) releasePlayer(inp);
        if (pending != null && pending != bestPlayer) releasePlayer(pending);
        if (pendOut != null && pendOut != bestPlayer) releasePlayer(pendOut);

        releaseAllFadingPlayers();

        if (deferredSwapRunnable != null) {
            mainHandler.removeCallbacks(deferredSwapRunnable);
            deferredSwapRunnable = null;
        }
        crossfadeInPlayer = null;
        pendingInPlayer = null;
        pendingOutPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        autoAdvanceCrossfadeActive = false;
        queueAdvancedByMonitor = false;
        monitorCrossfadeActive = false;
        outgoingFadePreStarted = false;
        deferredSwapPending = false;
        currentFadeInVolume = 0.0f;

        if (audioModeWasForced) {
            audioModeWasForced = false;
            restoreVideoModeSilently();
        }
    }

    private static void animateCrossfade(final ExoPlayerAccess inPlayer, final long durationOverrideMs) {
        try {
            inPlayer.patch_setVolume(0.0f);
        } catch (Exception e) {
            logWarn(()-> "fade-in pre-start: failed to zero volume: " + e.getMessage());
        }

        try { inPlayer.patch_setPlayWhenReady(true); } catch (Exception ignored) {}

        final long startTime = System.currentTimeMillis();
        final long duration = (durationOverrideMs > 0) ? durationOverrideMs : getCrossfadeDurationMs();

        logDebug(() -> "Crossfade fade-in started for @" + System.identityHashCode(inPlayer)
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
                        logDebug(() -> String.format(Locale.US,
                                "fade-in: t=%.2f inVol=%.2f(st=%d) fadingOut=%d",
                                t, inVol, inState, fadingOutPlayers.size()));
                    }
                } catch (Exception e) {
                    logError(()-> "Fade-in tick error", e);
                }

                if (t < 1.0f) {
                    mainHandler.postDelayed(this, TICK_MS);
                } else {
                    logDebug(() -> "Fade-in complete for @" + System.identityHashCode(inPlayer));
                    inVideoMode = false;
                    currentFadeInVolume = 1.0f;
                    try { inPlayer.patch_setVolume(1.0f); } catch (Exception ignored) {}

                    if (pendingInPlayer == null) {
                        crossfadeInProgress = false;
                        autoAdvanceCrossfadeActive = false;
                        queueAdvancedByMonitor = false;
                        monitorCrossfadeActive = false;
                        crossfadeInPlayer = null;
                        activeCoordinator = null;
                        audioModeWasForced = false;

                        startAutoAdvanceMonitor();
                    } else {
                        logDebug(() -> "Fade-in complete but pending player exists — "
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
                logDebug(() -> "Factory created player @"
                        + System.identityHashCode(player)
                        + " [created=" + playersCreated
                        + " released=" + playersReleased
                        + " outstanding="
                        + (playersCreated - playersReleased) + "]");
            }
            return (ExoPlayerAccess) player;
        } catch (Exception e) {
            logError(()-> "createPlayerViaFactory failed", e);
            return null;
        }
    }
    private static boolean isFromTaskRemoval() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("onTaskRemoved".equals(frame.getMethodName())) return true;
        }
        return false;
    }

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

    private static PlayerCoordinatorAccess getCoordinatorFromAtad(
            Object atadInstance) {
        try {
            MedialibPlayerAccess atad = (MedialibPlayerAccess) atadInstance;
            Object chain = atad.patch_getPlayerChain();
            if (chain == null) {
                logError(() -> "atad player chain is null");
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
            Object chainFinal = chain;
            logDebug(() -> "Traversed " + depthFinal + " delegates → "
                    + chainFinal.getClass().getName());

            if (chain instanceof PlayerCoordinatorAccess) {
                return (PlayerCoordinatorAccess) chain;
            }

            logError(() -> "Innermost class is not a PlayerCoordinatorAccess: "
                    + chainFinal.getClass().getName());
            return null;
        } catch (Exception e) {
            logError(()-> "getCoordinatorFromAtad error", e);
            return null;
        }
    }

        private record ForwardingHandler(Object target) implements InvocationHandler {

        @Override
            public Object invoke(Object proxy, Method method, Object[] args)
                    throws Throwable {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    throw (cause != null) ? cause : e;
                }
            }
        }

    private static Object unwrapForwardingTarget(Object listener) {
        while (Proxy.isProxyClass(listener.getClass())) {
            InvocationHandler h = Proxy.getInvocationHandler(listener);
            if (h instanceof ForwardingHandler) {
                listener = ((ForwardingHandler) h).target;
            } else {
                break;
            }
        }
        return listener;
    }

    private static Object createForwardingProxy(Object realListener) {
        Set<Class<?>> ifaceSet = new LinkedHashSet<>();
        for (Class<?> cls = realListener.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            ifaceSet.addAll(Arrays.asList(cls.getInterfaces()));
        }
        if (ifaceSet.isEmpty()) return null;
        Class<?>[] ifaces = ifaceSet.toArray(new Class<?>[0]);
        try {
            return Proxy.newProxyInstance(
                    realListener.getClass().getClassLoader(),
                    ifaces,
                    new ForwardingHandler(realListener));
        } catch (Exception e) {
            logWarn(()-> "createForwardingProxy failed: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void migrateListeners(ExoPlayerAccess fromPlayer, ExoPlayerAccess toPlayer) {
        try {
            Object fromSetObj = fromPlayer.patch_getListenerSet();
            if (!(fromSetObj instanceof CopyOnWriteArraySet)) {
                logWarn(()-> "migrateListeners: unexpected set type — clearing only");
                detachPlayerListeners(fromPlayer);
                return;
            }
            CopyOnWriteArraySet<Object> from = (CopyOnWriteArraySet<Object>) fromSetObj;

            List<Object> catSnapshot = new ArrayList<>(from);

            List<Object> realListeners = new ArrayList<>(catSnapshot.size());
            for (Object cat : catSnapshot) {
                if (cat instanceof ListenerWrapperAccess) {
                    Object raw = ((ListenerWrapperAccess) cat).patch_getWrappedListener();
                    if (raw != null) realListeners.add(unwrapForwardingTarget(raw));
                }
            }

            from.clear();

            Object toSetObj = toPlayer.patch_getListenerSet();
            CopyOnWriteArraySet<Object> toSet =
                    (toSetObj instanceof CopyOnWriteArraySet)
                    ? (CopyOnWriteArraySet<Object>) toSetObj : null;
            int toSizeBefore = toSet != null ? toSet.size() : -1;

            Set<Object> alreadyPresent = new HashSet<>();
            if (toSet != null) {
                for (Object cat : new ArrayList<>(toSet)) {
                    if (cat instanceof ListenerWrapperAccess) {
                        Object raw = ((ListenerWrapperAccess) cat).patch_getWrappedListener();
                        if (raw != null) alreadyPresent.add(unwrapForwardingTarget(raw));
                    }
                }
            }

            int registered = 0;
            int skipped = 0;
            for (Object real : realListeners) {
                if (alreadyPresent.contains(real)) {
                    skipped++;
                    continue;
                }
                if (coordinatorListenerBxi != null && real != coordinatorListenerBxi) {
                    skipped++;
                    continue;
                }
                Object proxy = createForwardingProxy(real);
                if (proxy == null) {
                    logWarn(()-> "migrateListeners: proxy creation returned null for "
                            + real.getClass().getName());
                    continue;
                }
                try {
                    toPlayer.patch_addListener(proxy);
                    registered++;
                    if (coordinatorListenerBxi == null) {
                        // Record the coordinator bxi on first successful migration.
                        coordinatorListenerBxi = real;
                        logDebug(() -> "migrateListeners: identified coordinator bxi: "
                                + real.getClass().getName()
                                + "@" + System.identityHashCode(real));
                    }
                } catch (Exception e) {
                    logWarn(()-> "migrateListeners: cau.add threw: " + e.getMessage());
                }
            }

            if (registered > 0 || skipped > 0) {
                final int registeredFinal = registered;
                final int skippedFinal = skipped;
                logDebug(() -> "migrateListeners: registered=" + registeredFinal
                        + " skipped=" + skippedFinal
                        + " total=" + realListeners.size()
                        + " toPlayer had=" + toSizeBefore
                        + " @" + System.identityHashCode(fromPlayer)
                        + " → @" + System.identityHashCode(toPlayer));
            } else {
                logWarn(()-> "migrateListeners: proxy path failed — copying " + catSnapshot.size()
                        + " original cats to toPlayer (had " + toSizeBefore + ")");
                if (toSet != null) toSet.addAll(catSnapshot);
            }
        } catch (Exception e) {
            logWarn(()-> "migrateListeners failed", e);
            detachPlayerListeners(fromPlayer);
        }
    }

    private static void detachPlayerListeners(ExoPlayerAccess player) {
        try {
            Object listenerSet = player.patch_getListenerSet();
            if (listenerSet instanceof CopyOnWriteArraySet) {
                ((CopyOnWriteArraySet<?>) listenerSet).clear();
                logDebug(() -> "Detached @" + System.identityHashCode(player)
                        + " from UI listeners (cleared listener set)");
            }
        } catch (Exception e) {
            logWarn(()-> "Could not detach player from UI listeners", e);
        }
    }

    private static void releasePlayer(ExoPlayerAccess p) {
        if (p == null) return;

        playersReleased++;
        logDebug(() -> "releasePlayer: @" + System.identityHashCode(p)
                + " [created=" + playersCreated + " released=" + playersReleased
                + " outstanding=" + (playersCreated - playersReleased) + "]");

        SharedCallbackAccess callback = activeSharedCallback;
        Object savedCqb = null, savedDlt = null;
        if (callback != null) {
            savedCqb = callback.patch_getCqb();
            savedDlt = callback.patch_getDlt();
        }

        try { p.patch_setDltCallback(null); } catch (Exception ignored) {}

        if (is9x) {
            try {
                p.patch_detachCwhFromEventDispatch();
                logDebug(() -> "releasePlayer: 9.x detached cwh from event dispatch on @"
                        + System.identityHashCode(p));
            } catch (Exception e) {
                logDebug(() -> "releasePlayer: 9.x cwh event dispatch detach failed: " + e.getMessage());
            }
        }

        if (is9x) suppressCwhU = true;
        try {
            p.patch_release();
        } catch (Exception e) {
            logDebug(() -> "releasePlayer: release() threw: " + e.getMessage());
        } finally {
            if (is9x) suppressCwhU = false;
        }

        if (callback != null) {
            Object postCqb = callback.patch_getCqb();
            Object postDlt = callback.patch_getDlt();
            if (savedCqb != null && postCqb == null) {
                callback.patch_setCqb(savedCqb);
                logDebug(() -> "releasePlayer: restored shared cqb");
            }
            if (savedDlt != null && postDlt == null) {
                callback.patch_setDlt(savedDlt);
                logDebug(() -> "releasePlayer: restored shared dlt");
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

    private static void cleanupAllPlayers() {
        logError(() -> "CLEANUP (emergency): " + dumpState());
        if (deferredSwapRunnable != null) {
            mainHandler.removeCallbacks(deferredSwapRunnable);
            deferredSwapRunnable = null;
        }
        releaseAllFadingPlayers();
        ExoPlayerAccess pi = pendingInPlayer;
        if (pi != null) { releasePlayer(pi); pendingInPlayer = null; }
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) { releasePlayer(po); pendingOutPlayer = null; }
        crossfadeInPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        if (autoAdvanceCrossfadeActive) {
            logWarn(()-> "cleanupAllPlayers: clearing autoAdvanceCrossfadeActive mid-fade " + dumpState());
        }
        autoAdvanceCrossfadeActive = false;
        queueAdvancedByMonitor = false;
        monitorCrossfadeActive = false;
        outgoingFadePreStarted = false;
        deferredSwapPending = false;
        currentFadeInVolume = 0.0f;
        coordinatorListenerBxi = null;
    }

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
                try { playerState = fp.player.patch_getPlaybackState(); } catch (Exception ignored) {}
                try {
                    fp.player.patch_setVolume(Math.max(0.0f, vol));
                    long elapsed = System.currentTimeMillis() - fp.startTimeMs;
                    if (elapsed % 500 < TICK_MS) {
                        final int playerStateFinal = playerState;
                        logDebug(() -> String.format(Locale.US,
                                "fade-out: @%d vol=%.2f state=%d elapsed=%dms",
                                System.identityHashCode(fp.player), vol, playerStateFinal, elapsed));
                    }
                } catch (Exception e) {
                    final int playerStateFinal = playerState;
                    logWarn(()-> "fade-out setVolume threw: " + e.getMessage()
                            + " player=@" + System.identityHashCode(fp.player)
                            + " state=" + playerStateFinal);
                }

                if (fp.isComplete()) {
                    try { fp.player.patch_setVolume(0.0f); } catch (Exception ignored) {}
                    final ExoPlayerAccess toRelease = fp.player;
                    mainHandler.postDelayed(() -> releasePlayer(toRelease), RELEASE_DRAIN_DELAY_MS);
                    it.remove();
                }
            }
        }

        if (!fadingOutPlayers.isEmpty()) {
            mainHandler.postDelayed(CrossfadeManager::tickFadingLoop, TICK_MS);
        } else {
            fadingLoopRunning = false;
            logDebug(() -> "Fading loop stopped — all fade-outs complete");
        }
    }

    /**
     * Injection point.
     */
    public static void onActivityStop() {
        activityRunning = false;
        logInfo(() -> "onActivityStop");
    }

    /**
     * Injection point.
     */
    public static void onActivityDestroy() {
        activityRunning = false;
        if (!crossfadeInProgress
                && pendingInPlayer == null
                && pendingOutPlayer == null
                && fadingOutPlayers.isEmpty()) {
            logInfo(() -> "onActivityDestroy — no in-flight crossfade state to release");
            return;
        }
        logInfo(() -> "onActivityDestroy — releasing in-flight crossfade state " + dumpState());
        stopAutoAdvanceMonitor();
        if (deferredSwapRunnable != null) {
            mainHandler.removeCallbacks(deferredSwapRunnable);
            deferredSwapRunnable = null;
        }
        releaseAllFadingPlayers();
        ExoPlayerAccess pi = pendingInPlayer;
        if (pi != null) { releasePlayer(pi); pendingInPlayer = null; }
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) { releasePlayer(po); pendingOutPlayer = null; }
        crossfadeInPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        autoAdvanceCrossfadeActive = false;
        queueAdvancedByMonitor = false;
        monitorTriggeredSkip = false;
        monitorCrossfadeActive = false;
        outgoingFadePreStarted = false;
        deferredSwapPending = false;
        currentFadeInVolume = 0.0f;
        coordinatorListenerBxi = null;
    }

    /**
     * Injection point.
     */
    public static void onActivityStart() {
        activityRunning = true;
        logInfo(() -> "onActivityStart");

        if (isCrossfadePaused) {
            logDebug(() -> "onActivityStart: auto-resetting isCrossfadePaused to false");
            isCrossfadePaused = false;
        }

        tryAttachLongPressHandler();

        if (isEnabled() && !isCrossfadePaused) {
            startAutoAdvanceMonitor();
        }
    }

    public static boolean isSessionPaused() {
        return isCrossfadePaused;
    }

    private static volatile long lastCastCheckMs = 0;
    private static volatile boolean lastCastResult = false;
    private static final long CAST_CHECK_TTL_MS = 250;

    @SuppressWarnings("deprecation")
    private static boolean isAudioRoutedToCast() {
        if (Build.VERSION.SDK_INT < 28) return false;

        long now = System.currentTimeMillis();
        if (now - lastCastCheckMs < CAST_CHECK_TTL_MS) {
            return lastCastResult;
        }
        lastCastCheckMs = now;

        boolean casting = false;
        StringBuilder probe = new StringBuilder();
        try {
            Context ctx = Utils.getContext();
            if (ctx != null) {
                MediaRouter mr = (MediaRouter) ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE);
                if (mr != null) {
                    MediaRouter.RouteInfo selected = mr.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
                    if (selected != null) {
                        int pt = selected.getPlaybackType();
                        probe.append("route{name=").append(selected.getName(ctx))
                                .append(",pbType=").append(pt).append("} ");
                        if (pt == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) {
                            casting = true;
                        }
                    } else {
                        probe.append("route{null} ");
                    }
                }
                if (!casting) {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                            int type = info.getType();
                            probe.append("dev{type=").append(type)
                                    .append(",id=").append(info.getId()).append("} ");
                            if (type == AudioDeviceInfo.TYPE_HDMI
                                    || type == AudioDeviceInfo.TYPE_HDMI_ARC
                                    || type == 29 /* TYPE_HDMI_EARC, API 31+ */
                                    || type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                                    || type == AudioDeviceInfo.TYPE_IP
                                    || type == AudioDeviceInfo.TYPE_BUS) {
                                casting = true;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logDebug(() -> "isAudioRoutedToCast check failed: " + e.getMessage());
        }

        if (casting != lastCastResult) {
            final boolean castingFinal = casting;
            logDebug(() -> "Cast routing " + (castingFinal ? "ENGAGED" : "RELEASED")
                    + " — crossfade " + (castingFinal ? "disabled" : "re-enabled")
                    + " [" + probe.toString().trim() + "]");
        }

        lastCastResult = casting;
        return casting;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private static synchronized void toggleSessionPause() {
        isCrossfadePaused = !isCrossfadePaused;
        boolean isPaused = isCrossfadePaused;

        logDebug(() -> "Session " + (isPaused ? "PAUSED" : "RESUMED")
                + " [inVideo=" + isCurrentlyInVideoMode()
                + " inProgress=" + crossfadeInProgress + "]");

        if (isCrossfadePaused) {
            audioModeWasForced = false;
            abortCrossfadeNow();
            stopAutoAdvanceMonitor();
        } else {
            startAutoAdvanceMonitor();
        }

        Context ctx = Utils.getContext();
        if (ctx != null) {
            try {
                Vibrator vib;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    @SuppressLint("WrongConstant")
                    VibratorManager vibratorManager = (VibratorManager)
                            ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    vib = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
                } else {
                    vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                }

                if (vib != null && vib.hasVibrator()) {
                    VibrationEffect effect =
                            VibrationEffect.createOneShot(100,
                                    VibrationEffect.DEFAULT_AMPLITUDE);
                    vib.vibrate(effect);
                }
            } catch (Exception ignored) {}

            Utils.showToastShort(str(isCrossfadePaused
                    ? "morphe_music_crossfade_paused_toast"
                    : "morphe_music_crossfade_resumed_toast"));
        }
    }

    public static boolean isCrossfadeActive() {
        return isEnabled() && !isCrossfadePaused;
    }

    /**
     * Injection point.
     */
    public static void onNbaCreated(Object nba) {
        lastNbaRef = new WeakReference<>(nba);
        logDebug(() -> "onNbaCreated: nba captured @" + System.identityHashCode(nba)
                + " class=" + nba.getClass().getSimpleName());
    }

    /**
     * Injection point.
     */
    public static boolean shouldBlockVideoToggle(Object nba) {
        lastNbaRef = new WeakReference<>(nba);
        if (internalToggle) return false;
        tryAttachLongPressHandler();
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            boolean isAudioMode = toggle.patch_isAudioMode();

            logDebug(() -> "videoToggle: isAudioMode=" + isAudioMode
                    + " enabled=" + isEnabled() + " paused=" + isCrossfadePaused
                    + " inVideoMode(before)=" + inVideoMode);

            if (!isEnabled()) {
                logDebug(() -> "videoToggle → ALLOW (crossfade disabled)");
                return false;
            }

            if (isCrossfadePaused) {
                if (isAudioMode) {
                    try {
                        ((VideoToggleAccess) nba).patch_restoreVideoMode();
                        inVideoMode = true;
                        audioModeWasForced = false;
                        manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
                        logDebug(() -> "videoToggle → INTERCEPTED (audio→video while paused) — applied broadcast restoreVideoMode");
                        return true;
                    } catch (Exception e) {
                        logWarn(()-> "videoToggle intercept failed, allowing natural toggle: " + e.getMessage());
                        return false;
                    }
                }
                manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
                logDebug(() -> "videoToggle → ALLOW (video→audio while paused)");
                return false;
            }

            if (isAudioMode) {
                logDebug(() -> "videoToggle → BLOCK (audio→video while crossfade active)");
                Utils.showToastShort(str("morphe_music_crossfade_video_mode_disabled_toast"));
                return true;
            }

            inVideoMode = false;
            manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
            logDebug(() -> "videoToggle → ALLOW (video→audio, suppressing crossfade for 500ms)");
            return false;
        } catch (Exception e) {
            logWarn(()-> "Could not check video toggle state", e);
            return false;
        }
    }

    private static Object findNbaInChain() {
        Object atad = lastAtadRef != null ? lastAtadRef.get() : null;
        if (atad == null) return null;
        try {
            MedialibPlayerAccess player = (MedialibPlayerAccess) atad;
            Object chain = player.patch_getPlayerChain();
            int depth = 0;
            while (chain != null) {
                if (chain instanceof VideoToggleAccess) {
                    final int depthFinal = depth;
                    final Object chainFinal = chain;
                    logDebug(() -> "findNbaInChain: found nba @" + System.identityHashCode(chainFinal)
                            + " class=" + chainFinal.getClass().getSimpleName()
                            + " at depth=" + depthFinal);
                    lastNbaRef = new WeakReference<>(chain);
                    return chain;
                }
                if (!(chain instanceof DelegateAccess)) break;
                Object next = ((DelegateAccess) chain).patch_getDelegate();
                if (next == null || next == chain) break;
                chain = next;
                depth++;
            }
        } catch (Exception e) {
            logDebug(() -> "findNbaInChain error: " + e.getMessage());
        }
        logWarn(()-> "findNbaInChain: nba not found in delegate chain — audio/video mode unknown");
        return null;
    }

    private static void forceAudioModeIfNeeded() {
        Object nba = lastNbaRef.get();
        if (nba == null) {
            nba = findNbaInChain();
        }
        if (nba == null) {
            logWarn(()-> "forceAudioModeIfNeeded: nba not found — cannot force audio mode. "
                    + "Video mode may be active. " + dumpState());
            return;
        }
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            if (!toggle.patch_isAudioMode()) {
                toggle.patch_forceAudioModeSilent();
                inVideoMode = false;
                audioModeWasForced = true;
                logDebug(() -> "Silently forced audio mode (no reactive broadcast to nmi)");
            }
        } catch (Exception e) {
            logWarn(()-> "Could not force audio mode: " + e.getMessage());
        }
    }

    private static void forceAudioModeBroadcastIfNeeded() {
        Object nba = lastNbaRef.get();
        if (nba == null) {
            nba = findNbaInChain();
        }
        if (nba == null) {
            logWarn(()-> "forceAudioModeBroadcastIfNeeded: nba not found — cannot force audio mode");
            return;
        }
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            if (!toggle.patch_isAudioMode()) {
                toggle.patch_forceAudioMode();
                inVideoMode = false;
                audioModeWasForced = true;
                logDebug(() -> "Broadcast forced audio mode — nmi subscribers will reconcile, song will reload as audio-only");
            }
        } catch (Exception e) {
            logWarn(()-> "Could not broadcast force audio mode: " + e.getMessage());
        }
    }

    private static void restoreVideoModeSilently() {
        Object nba = lastNbaRef.get();
        if (nba == null) return;
        try {
            ((VideoToggleAccess) nba).patch_restoreVideoModeSilent();
            inVideoMode = true;
            logDebug(() -> "Silently restored video mode preference (ready for next crossfade)");
        } catch (Exception e) {
            logWarn(()-> "Could not restore video mode: " + e.getMessage());
        }
    }

    private static boolean isCurrentlyInVideoMode() {
        Object nba = lastNbaRef != null ? lastNbaRef.get() : null;
        if (nba == null) {
            nba = findNbaInChain();
        }
        if (nba != null) {
            try {
                VideoToggleAccess toggle = (VideoToggleAccess) nba;
                boolean isAudio = toggle.patch_isAudioMode();
                inVideoMode = !isAudio;
                return !isAudio;
            } catch (Exception e) {
                logDebug(() -> "Could not query live video mode: " + e.getMessage());
            }
        }
        logWarn(()-> "isCurrentlyInVideoMode: nba not in chain — returning cached inVideoMode=" + inVideoMode
                + " (may be stale)");
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

    private static final String[] SHUFFLE_IDS = {
            "queue_shuffle_button",
            "queue_shuffle",
            "playback_queue_shuffle_button_view",
            "overlay_queue_shuffle_button_view"
    };

    private static Runnable pendingLongPress;
    private static final boolean longPressHandled = false;
    private static ViewTreeObserver.OnGlobalLayoutListener longPressLayoutListener;
    private static WeakReference<View> longPressLayoutListenerHost = new WeakReference<>(null);
    private static volatile boolean pendingLongPressAttach = false;

    private static void tryAttachLongPressHandler() {
        if (!isSessionControlEnabled() || !isEnabled()) return;
        if (pendingLongPressAttach) return;
        pendingLongPressAttach = true;

        mainHandler.post(() -> {
            pendingLongPressAttach = false;
            tryAttachLongPressNow();
            registerLongPressLayoutListener();
        });
    }

    private static void tryAttachLongPressNow() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.getWindow() == null) return;

            View decorView = activity.getWindow().getDecorView();
            Resources res = activity.getResources();
            String pkg = activity.getPackageName();

            List<View> allButtons = new ArrayList<>();
            List<String> matchedIds = new ArrayList<>();
            for (String idName : SHUFFLE_IDS) {
                int id = res.getIdentifier(idName, "id", pkg);
                if (id == 0) continue;
                List<View> matched = new ArrayList<>();
                findAllViewsById(decorView, id, matched);
                if (!matched.isEmpty()) {
                    matchedIds.add(idName + "(" + matched.size() + ")");
                }
                allButtons.addAll(matched);
            }

            if (allButtons.isEmpty()) return;

            StringBuilder attachLog = new StringBuilder("Long-press attach: matched=" + matchedIds + " — attaching to:");
            for (View shuffleBtn : allButtons) {
                attachTouchLongPress(shuffleBtn, "btn");
                attachLog.append(" btn@").append(System.identityHashCode(shuffleBtn))
                        .append("(").append(shuffleBtn.getClass().getSimpleName())
                        .append(" vis=").append(shuffleBtn.getVisibility())
                        .append(" clickable=").append(shuffleBtn.isClickable())
                        .append(")");

                View parent = (View) shuffleBtn.getParent();
                if (parent != null && parent != decorView) {
                    attachTouchLongPress(parent, "parent");
                    attachLog.append(" parent@").append(System.identityHashCode(parent))
                            .append("(").append(parent.getClass().getSimpleName()).append(")");
                }
            }
            logDebug(attachLog::toString);
        } catch (Exception e) {
            logDebug(() -> "tryAttachLongPressNow exception: " + e.getMessage());
        }
    }

    private static void registerLongPressLayoutListener() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.getWindow() == null) return;

            View decorView = activity.getWindow().getDecorView();
            View prevHost = longPressLayoutListenerHost.get();
            if (longPressLayoutListener != null && prevHost == decorView) return;
            if (longPressLayoutListener != null && prevHost != null
                    && prevHost.getViewTreeObserver() != null
                    && prevHost.getViewTreeObserver().isAlive()) {
                try {
                    prevHost.getViewTreeObserver().removeOnGlobalLayoutListener(longPressLayoutListener);
                } catch (Exception ignored) {}
            }
            longPressLayoutListener = CrossfadeManager::tryAttachLongPressNow;
            longPressLayoutListenerHost = new WeakReference<>(decorView);
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(longPressLayoutListener);
            logDebug(() -> "Long-press attach: registered GlobalLayoutListener");
        } catch (Exception e) {
            logDebug(() -> "registerLongPressLayoutListener exception: " + e.getMessage());
        }
    }

    private static void findAllViewsById(View root, int id,
                                          List<View> out) {
        if (root.getId() == id) out.add(root);
        if (root instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAllViewsById(vg.getChildAt(i), id, out);
            }
        }
    }

    private static void attachTouchLongPress(View btn, String tag) {
        final int viewId = System.identityHashCode(btn);

        btn.setOnLongClickListener(v -> {
            toggleSessionPause();
            logDebug(() -> "Shuffle long-press fired on " + tag + "@" + viewId);
            return true;
        });
        btn.setLongClickable(true);
    }

}

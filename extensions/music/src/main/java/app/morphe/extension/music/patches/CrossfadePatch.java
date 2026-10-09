/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1065
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
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
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

/**
 * Crossfades by keeping the outgoing ExoPlayer alive and loading the next track into a second
 * player built by YouTube Music's own factory, so it gets the same DRM and data source setup.
 */
@SuppressWarnings("unused")
public class CrossfadePatch {

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
        /**
         * Lives in the player's audio offload listener set, not in its ListenerSet.
         */
        Object patch_getCoordinatorListener();
    }

    public interface ExoPlayerAccess {
        int patch_getPlaybackState();
        long patch_getCurrentPosition();
        long patch_getDuration();
        void patch_setVolume(float volume);
        void patch_setPlayWhenReady(boolean play);
        void patch_setVideoSurface(Surface surface);
        void patch_release();
        Object patch_getListenerSet();
        void patch_setDltCallback(Object dlt);
        void patch_addDirectListener(Object listener);
        void patch_removeDirectListener(Object listener);
        /**
         * Called on the outgoing player before release, so its isPlayingChanged(false)
         * does not reach the MediaSession.
         */
        void patch_detachCwhFromEventDispatch();
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
        void patch_loadVideoWith(Object descriptor);
    }

    public interface VideoToggleAccess {
        boolean patch_isAudioMode();
        void patch_forceAudioModeSilent();
        void patch_restoreVideoModeSilent();
        /**
         * Notifies subscribers, so ones left stale by the silent variants resync.
         */
        void patch_restoreVideoMode();
    }

    public interface DelegateAccess {
        Object patch_getDelegate();
    }

    private static void logDebug(Logger.LogMessage msg) {
        Logger.printDebug(msg);
    }

    private static void logInfo(Logger.LogMessage msg) {
        Logger.printInfo(msg);
    }

    /**
     * Recoverable problems are logged as info, since printException shows an error toast.
     */
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
        String name = switch (reason) {
            case 1 -> "STOP";
            case 2 -> "PAUSE";
            case 3 -> "END_OF_CONTENT";
            case 4 -> "ERROR";
            case 5 -> "DIRECTOR_RESET/SKIP";
            case 6 -> "SEEK";
            case 7 -> "QUEUE_CHANGED";
            case 8 -> "PLAYLIST_CHANGED";
            case 12 -> "RESET_INTERNALLY";
            default -> "UNKNOWN";
        };
        return name + "(" + reason + ")";
    }

    private static String dumpState() {
        return "STATE["
                + "inProgress=" + crossfadeInProgress
                + " autoAdv=" + autoAdvanceCrossfadeActive
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

    /**
     * A switch instead of per-constant bodies, because EnumSetting cannot read the
     * constants of anonymous enum subclasses.
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

    private static volatile boolean isCrossfadePaused = false;
    private static volatile boolean inVideoMode = false;
    private static volatile long manualToggleSuppressionUntil = 0;
    private static volatile boolean crossfadeInProgress = false;
    private static volatile boolean audioModeWasForced = false;
    private static volatile boolean activityRunning = false;
    /**
     * Keeps a crossfade from resuming an outgoing player the user had paused.
     */
    private static volatile boolean playerIsPlaying = true;
    /**
     * Tells the natural track-end stopVideo(5) apart from a real double skip.
     */
    private static volatile boolean autoAdvanceCrossfadeActive = false;
    /**
     * The outgoing fade-out already started at swap time, so loading latency of the new
     * player cannot eat into it.
     */
    private static volatile boolean outgoingFadePreStarted = false;
    /**
     * Its fade-out waits for the next track's load, so a load that never comes (offline)
     * does not fade the end of the song away (#2433).
     */
    private static volatile ExoPlayerAccess outgoingAwaitingLoad = null;

    /**
     * Keeps the release of an outgoing player from releasing the shared cwh listener set.
     */
    public static volatile boolean suppressCwhU = false;

    private static final Runnable NO_OP_RUNNABLE = () -> {};

    /**
     * Injection point. 9.28+ inlines cwh.U() into ExoPlayer.release(), so the release
     * Runnable is swapped for a no-op here instead of returning early from cwh.U().
     */
    public static Runnable filterAnalyticsRelease(Runnable runnable) {
        return suppressCwhU ? NO_OP_RUNNABLE : runnable;
    }

    /**
     * Changing the setting restarts the app, so it is read once.
     */
    private static final boolean CROSSFADE_ENABLED = Settings.CROSSFADE_ENABLED.get();

    /**
     * The internal second stopVideo(5) arrives about 1 ms after the first, a real double skip 200 ms+.
     */
    private static volatile long swapStartTimeMs = 0L;

    private static final long INTERNAL_CALL_WINDOW_MS = 100L;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int TICK_MS = 50;
    /**
     * Lets the AudioTrack drain its buffered frames, so the release does not cut queued audio.
     */
    private static final long RELEASE_DRAIN_DELAY_MS = 150;
    private static final int READY_POLL_MS = 100;
    private static final int READY_TIMEOUT_MS = 10000;
    private static final int STATE_IDLE = 1;
    private static final int STATE_ENDED = 4;
    private static final int STATE_READY = 3;
    private static final int REASON_DIRECTOR_RESET = 5;

    /**
     * A pending player idle this long with no load issued means the queue was dismissed (#1671).
     * Background throttling can delay the load past 600 ms, so this is kept generous.
     */
    private static final long IDLE_LOAD_FAIL_MS = 2000;

    /**
     * A dismissal stops playback with the same stopVideo(5) as a skip, and on 9.x it arrives
     * asynchronously. A dismissal empties the queue, so a real skip cannot follow this quickly.
     */
    private static final long DISMISS_WINDOW_MS = 1500;
    private static volatile long dismissWindowUntilMs = 0;
    private static final long AUTO_ADVANCE_THRESHOLD_MS = 5000;
    private static final long MONITOR_POLL_MS = 100;
    /**
     * Covers poll granularity and the new player's time to READY, so the fade-out ends before the old track does.
     */
    private static final long AUTO_ADVANCE_TRIGGER_BUFFER_MS = 300;
    private static final int QUICK_FADE_MS = 400;

    private static volatile SharedCallbackAccess activeSharedCallback = null;
    private static volatile ExoPlayerAccess crossfadeInPlayer = null;
    private static volatile ExoPlayerAccess pendingInPlayer = null;
    private static volatile ExoPlayerAccess pendingOutPlayer = null;
    private static volatile PlayerCoordinatorAccess activeCoordinator = null;
    private static volatile float currentFadeInVolume = 0.0f;

    private static final List<FadingPlayer> fadingOutPlayers =
            Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean fadingLoopRunning = false;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static WeakReference<Object> lastNbaRef = new WeakReference<>(null);
    private static volatile boolean internalPlayNext = false;
    private static volatile boolean monitorTriggeredSkip = false;
    /**
     * The outgoing's natural-end stopVideo(5) is then blocked, or gapless playback would
     * advance the queue a second time.
     */
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

        /**
         * Linear, because a demoted incoming player starts from a partial volume.
         */
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

    public static boolean onBeforeStopVideo(Object atadInstance, int reason) {
        if (!CROSSFADE_ENABLED) return false;

        // A crossfade here would create a player that never loads and keep the old track
        // playing (#1671). The window expires by time to cover every stop of the dismissal.
        if (SystemClock.uptimeMillis() < dismissWindowUntilMs) {
            logInfo(() -> "stopVideo(" + reason + "): within dismiss window, passing through, no crossfade");
            return false;
        }

        // The cast receiver would see the MediaSession flicker of a player swap (#1549).
        // A crossfade already running when casting starts is left to finish.
        if (!crossfadeInProgress && isAudioRoutedToCast()) {
            logDebug(() -> "stopVideo(" + reason + "): skip, audio routed to cast/mirror (#1549)");
            return false;
        }

        int atadId = System.identityHashCode(atadInstance);
        if (atadId != lastAtadIdentity && lastAtadIdentity != 0) {
            logDebug(() -> "QUEUE-CHANGE DETECTED: atad identity changed @"
                    + lastAtadIdentity + " -> @" + atadId
                    + " (new session/queue) " + dumpState());
        }
        lastAtadIdentity = atadId;
        lastAtadRef = new WeakReference<>(atadInstance);
        tryAttachLongPressHandler();

        if (crossfadeInProgress) {
            if (reason == REASON_DIRECTOR_RESET) {
                if (autoAdvanceCrossfadeActive) {
                    if (queueAdvancedByMonitor) {
                        // The queue already advanced, letting this through would skip a track.
                        logDebug(() -> "stopVideo(5): auto-advance + queue already advanced, BLOCKING natural-end");
                        return true;
                    }
                    return false;
                }
                handleChainedSkip(atadInstance);
                return false;
            }
            // These load the next track onto the new player.
            logDebug(() -> "stopVideo/" + stopReasonName(reason) + ": ALLOW, 9.x native cycle (crossfade in progress)");
            return false;
        }

        if (reason != REASON_DIRECTOR_RESET) {
            if (reason == lastLoggedReason) {
                suppressedReasonCount++;
            } else {
                if (suppressedReasonCount > 0) {
                    logDebug(() -> "  (suppressed " + suppressedReasonCount
                                        + " duplicate reason=" + lastLoggedReason + " entries)");
                }
                logDebug(() -> "stopVideo reason=" + reason + ", not a skip, ignoring");
                lastLoggedReason = reason;
                suppressedReasonCount = 0;
            }
            return false;
        }
        lastLoggedReason = -1;
        suppressedReasonCount = 0;

        if (System.currentTimeMillis() < manualToggleSuppressionUntil) {
            logDebug(() -> "stopVideo(5): skip, within manual toggle suppression window");
            return false;
        }

        if (isCrossfadePaused || getCrossfadeDurationMs() <= 0) {
            logDebug(() -> "stopVideo(5): skip [paused=" + isCrossfadePaused
                    + " inVideo=" + isCurrentlyInVideoMode() + "]");
            return false;
        }

        if (isFromTaskRemoval()) {
            logDebug(() -> "stopVideo(5): skip, triggered by onTaskRemoved (activity killed)");
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

            // The monitor flag is checked first. With fades of 5 s or longer the remaining time
            // exceeds the threshold, and the stop would be taken for a manual skip.
            boolean isAutoAdvance = queueAdvancedByMonitor;
            try {
                long pos = currentExo.patch_getCurrentPosition();
                long duration = currentExo.patch_getDuration();
                long remaining = (duration > 0) ? duration - pos : Long.MAX_VALUE;
                // The natural end can fire before the monitor does.
                if (!isAutoAdvance) {
                    isAutoAdvance = duration > 0 && remaining >= 0
                            && remaining < AUTO_ADVANCE_THRESHOLD_MS;
                }
                final boolean isAutoAdvanceFinal = isAutoAdvance;
                logDebug(() -> "stopVideo(5): pos=" + pos + "ms dur=" + duration
                        + "ms remaining=" + remaining
                        + "ms queueAdvancedByMonitor=" + queueAdvancedByMonitor
                        + " -> " + (isAutoAdvanceFinal ? "AUTO-ADVANCE" : "MANUAL SKIP"));
            } catch (Exception e) {
                final boolean isAutoAdvanceFinal = isAutoAdvance;
                logWarn(() -> "Could not read position/duration, assuming "
                        + (isAutoAdvanceFinal ? "auto-advance" : "manual skip"), e);
            }

            if (isAutoAdvance && (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get() || sleepTimerEndOfTrack)) {
                logDebug(() -> "stopVideo(5): skip, auto-advance crossfade disabled or end of song sleep timer");
                return false;
            }
            if (!isAutoAdvance && !Settings.CROSSFADE_ON_SKIP.get()) {
                logDebug(() -> "stopVideo(5): skip, manual skip crossfade disabled");
                return false;
            }

            // SABR feeds the outgoing only through YouTube Music's session, which stops on the track
            // change. The old song falls silent within about a second, whatever its buffer reports (#2748).
            if (isSabrStream()) {
                logDebug(() -> "stopVideo(5): skip, SABR stream");
                showSabrToastOnce();
                return false;
            }

            // The monitor is already fading the outgoing out, the natural transition creates the incoming.
            if (isAutoAdvance && autoAdvanceCrossfadeActive) {
                logDebug(() -> "9.x: volume-fade auto-advance, allowing stopVideo(5) (outgoing fade running)");
                return false;
            }
            // A pre-started fade-out keeps running on its own player, the skip has a new outgoing.
            if (!isAutoAdvance && autoAdvanceCrossfadeActive) {
                autoAdvanceCrossfadeActive = false;
                queueAdvancedByMonitor = false;
                outgoingFadePreStarted = false;
                logDebug(() -> "9.x: manual skip aborted volume-fade, proceeding with normal crossfade");
            }

            boolean wasInVideoMode = isCurrentlyInVideoMode();

            logDebug(() -> "stopVideo(5): STARTING crossfade [paused=" + isCrossfadePaused
                        + " wasInVideo=" + wasInVideoMode
                        + "]");

            int currentState = currentExo.patch_getPlaybackState();
            logDebug(() -> "Current player state=" + currentState
                    + " class=" + currentExo.getClass().getName());

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                logDebug(() -> "Silent audio mode set BEFORE factory (video->audio, no nmi broadcast)");
            }

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            beginCrossfade(coordinator, currentExo, newExo);
            if (isAutoAdvance) {
                autoAdvanceCrossfadeActive = true;
                // The outgoing ends during the fade, and its onEnded would be routed to the
                // incoming and advance the queue again. A manual skip keeps it attached.
                detachCwh(currentExo);
            }
            swapCoordinatorPlayer(coordinator, currentExo, newExo);

            // A pauseVideo right before this stop is part of the skip, not a user pause,
            // which comes seconds before picking another song.
            long msSincePause = System.currentTimeMillis() - lastPauseVideoMs;
            boolean outgoingWasPlaying = playerIsPlaying || msSincePause < PAUSE_TO_STOP_INTERNAL_WINDOW_MS;
            logDebug(() -> "outgoing re-enable check: playerIsPlaying=" + playerIsPlaying
                    + " msSincePause=" + msSincePause + "ms -> wasPlaying=" + outgoingWasPlaying);
            try {
                if (outgoingWasPlaying) {
                    currentExo.patch_setPlayWhenReady(true);
                    currentExo.patch_setVolume(1.0f);
                    logDebug(() -> "9.x: re-enabled outgoing player @" + System.identityHashCode(currentExo));
                } else {
                    currentExo.patch_setVolume(0.0f);
                    logDebug(() -> "9.x: outgoing player was paused (genuine), keeping silent @"
                            + System.identityHashCode(currentExo));
                }
            } catch (Exception e) {
                logWarn(()-> "9.x: could not configure outgoing player: " + e.getMessage());
            }

            if (isAutoAdvance && outgoingWasPlaying) {
                outgoingAwaitingLoad = currentExo;
            }

            pollForNewTrackReady(newExo);
            // The native stop chain loads the track onto the new player.
            return false;

        } catch (Exception e) {
            logError(()-> "onBeforeStopVideo error", e);
            resetCrossfade();
            return false;
        }
    }

    private static void handleChainedSkip(Object atadInstance) {
        // Teardown sends bursts of stopVideo(5). Players created for them never get READY,
        // and onActivityDestroy cleans up the running crossfade.
        if (!activityRunning) {
            logInfo(() -> "CHAINED SKIP suppressed, activity not running (likely teardown)");
            return;
        }
        logDebug(() -> "stopVideo(5): CHAINED SKIP, creating new player, deferring demotion until READY");

        long elapsed = System.currentTimeMillis() - swapStartTimeMs;
        if (elapsed < INTERNAL_CALL_WINDOW_MS) {
            logDebug(() -> "9.x: internal second stopVideo(5) after " + elapsed
                            + "ms, allowing through");
            return;
        }

        if (isCrossfadePaused || getCrossfadeDurationMs() <= 0) {
            logDebug(() -> "Chained skip: crossfade now paused, aborting crossfade");
            abortCrossfadeNow();
            return;
        }

        try {
            PlayerCoordinatorAccess coordinator = activeCoordinator;
            if (coordinator == null) {
                coordinator = getCoordinatorFromAtad(atadInstance);
                if (coordinator == null) {
                    logError(() -> "Chained skip: coordinator null, aborting");
                    abortCrossfadeNow();
                    return;
                }
            }

            ExoPlayerAccess oldPending = pendingInPlayer;
            pendingInPlayer = null;

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                logError(() -> "Chained skip: factory failed, aborting crossfade");
                if (oldPending != null) {
                    detachPlayerListeners(oldPending);
                    releasePlayer(oldPending);
                }
                abortCrossfadeNow();
                return;
            }

            newExo.patch_setVolume(0.0f);
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;

            // The coordinator still points at oldPending, so it is swapped before the release.
            swapCoordinatorPlayer(coordinator, oldPending, newExo);
            if (oldPending != null) {
                logDebug(() -> "Chained skip: releasing old pending @"
                        + System.identityHashCode(oldPending)
                        + " (never reached READY)");
                detachPlayerListeners(oldPending);
                releasePlayer(oldPending);
            }

            pollForNewTrackReady(newExo);
        } catch (Exception e) {
            logError(()-> "handleChainedSkip error", e);
            abortCrossfadeNow();
        }
    }

    private static void beginCrossfade(PlayerCoordinatorAccess coordinator,
                                       ExoPlayerAccess outgoing, ExoPlayerAccess incoming) {
        incoming.patch_setVolume(0.0f);
        pendingOutPlayer = outgoing;
        pendingInPlayer = incoming;
        activeCoordinator = coordinator;
        crossfadeInProgress = true;
        swapStartTimeMs = System.currentTimeMillis();
    }

    /**
     * The coordinator's offload listener does not move with the swap. Left on an outgoing
     * factory player its stop clears the queue, and without it on the incoming the
     * MediaSession never receives onIsPlayingChanged(true).
     */
    private static void swapCoordinatorPlayer(PlayerCoordinatorAccess coordinator,
                                              ExoPlayerAccess outgoing, ExoPlayerAccess incoming) {
        // In video mode the outgoing keeps rendering into the surface that is rebound for the
        // next track, and the failing video decoder stops its audio as well.
        if (outgoing != null) {
            try {
                outgoing.patch_setVideoSurface(null);
            } catch (Exception e) {
                logWarn(() -> "swap: clearing outgoing video surface failed: " + e.getMessage());
            }
        }

        Object listener = null;
        try {
            listener = coordinator.patch_getCoordinatorListener();
            if (listener != null && outgoing != null) {
                outgoing.patch_removeDirectListener(listener);
            }
        } catch (Exception e) {
            logWarn(() -> "swap: removing coordinator listener failed: " + e.getMessage());
        }

        coordinator.patch_setPlayerWithBindings(incoming);
        logDebug(() -> "swap: coordinator -> @" + System.identityHashCode(incoming)
                + " (from @" + System.identityHashCode(outgoing) + ")");

        if (listener != null) {
            try {
                incoming.patch_addDirectListener(listener);
            } catch (Exception e) {
                logWarn(() -> "swap: adding coordinator listener failed: " + e.getMessage());
            }
        }
        VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
        if (surface != null) {
            surface.patch_setPlayerReference(incoming);
        }
    }

    private static void detachCwh(ExoPlayerAccess player) {
        try {
            player.patch_detachCwhFromEventDispatch();
        } catch (Exception e) {
            logWarn(() -> "detachCwh failed on @" + System.identityHashCode(player) + ": " + e.getMessage());
        }
    }

    /**
     * Started when the load is issued, not at READY: the new player loads cold, which can take
     * longer than the outgoing has left, so waiting for READY would shorten or skip the fade-out.
     */
    private static void preStartOutgoingFadeOut(ExoPlayerAccess outgoing) {
        long duration = getCrossfadeDurationMs();
        fadingOutPlayers.add(new FadingPlayer(outgoing, duration, Settings.CROSSFADE_CURVE.get()));
        outgoingFadePreStarted = true;
        ensureFadingLoopRunning();
        logDebug(() -> "pre-started outgoing fade-out @" + System.identityHashCode(outgoing)
                + " over " + duration + "ms");
    }

    /**
     * Native SABR playback or a spoofed client that falls back to SABR.
     */
    private static boolean isSabrStream() {
        return !SpoofVideoStreamsPatch.disableSABR();
    }

    private static volatile boolean sabrToastShown = false;

    private static void showSabrToastOnce() {
        if (sabrToastShown) return;
        sabrToastShown = true;
        Utils.showToastShort(str("morphe_music_crossfade_sabr_toast"));
    }

    private static ExoPlayerAccess createNewPlayer(PlayerCoordinatorAccess coordinator) {
        try {
            SessionAccess session = (SessionAccess) coordinator.patch_getSession();
            PlayerFactoryAccess factory = session == null ? null : (PlayerFactoryAccess) session.patch_getFactory();
            Object loadControl = coordinator.patch_getLoadControl();
            SharedStateAccess sharedState = (SharedStateAccess) coordinator.patch_getSharedState();
            SharedCallbackAccess sharedCallback = (SharedCallbackAccess) coordinator.patch_getSharedCallback();
            if (factory == null || loadControl == null || sharedState == null || sharedCallback == null) {
                logError(() -> "createNewPlayer: missing session=" + (session != null)
                        + " factory=" + (factory != null) + " loadControl=" + (loadControl != null)
                        + " sharedState=" + (sharedState != null) + " sharedCallback=" + (sharedCallback != null));
                return null;
            }
            activeSharedCallback = sharedCallback;

            Object oldTimeline = sharedState.patch_getTimeline();
            Object oldCqb = sharedCallback.patch_getCqb();
            logDebug(() -> "Pre-factory shared state: cqb=" + (oldCqb != null));
            sharedState.patch_setTimeline(null);
            sharedCallback.patch_setCqb(null);

            ExoPlayerAccess newExo = createPlayerViaFactory(factory, coordinator, loadControl);
            if (newExo == null) {
                logError(() -> "Factory returned null, restoring");
                sharedState.patch_setTimeline(oldTimeline);
                sharedCallback.patch_setCqb(oldCqb);
                return null;
            }

            Object postTimeline = sharedState.patch_getTimeline();
            Object postCqb = sharedCallback.patch_getCqb();
            logDebug(() -> "Post-factory shared state: cqb=" + (postCqb != null)
                    + " newExo=" + System.identityHashCode(newExo));
            if (postTimeline == null) {
                logWarn(()-> "Factory did not re-set timeline (expected on 9.x, field is final, restoring)");
                sharedState.patch_setTimeline(oldTimeline);
            }
            if (postCqb == null) {
                logError(() -> "Factory failed to set cqb, aborting");
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
     * Injection point. Returns true to block the native call, which is then re-invoked
     * internally so the next track loads onto the new player.
     */
    public static boolean onBeforePlayNext(Object coordinatorInstance) {
        if (!CROSSFADE_ENABLED) return false;

        if (!crossfadeInProgress && isAudioRoutedToCast()) {
            logDebug(() -> "playNext: skip, audio routed to cast/mirror (#1549)");
            return false;
        }

        // The native call then fires stopVideo(5), where onBeforeStopVideo starts the crossfade.
        if (monitorTriggeredSkip) {
            monitorTriggeredSkip = false;
            logDebug(() -> "PlayNext: monitor-triggered, allowing native auih.y()V (stopVideo intercepted by onBeforeStopVideo)");
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
                // Gapless playback calls this again after the monitor, which would load the
                // track after next onto the incoming player.
                logDebug(() -> "PlayNext: auto-advance crossfade in progress, blocking duplicate native call");
                return true;
            }
            return false;
        }

        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get() || sleepTimerEndOfTrack || isSabrStream()) {
            logDebug(() -> "PlayNext: skip, auto-advance crossfade disabled, end of song sleep timer or SABR");
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

            beginCrossfade(coordinator, currentExo, newExo);
            autoAdvanceCrossfadeActive = true;
            swapCoordinatorPlayer(coordinator, currentExo, newExo);

            if (wasInVideoMode) {
                forceAudioModeIfNeeded();
                logDebug(() -> "PlayNext: forced audio mode for incoming track (was in video mode)");
            }

            // Advances the queue so the native loadVideo targets the new player.
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
                logWarn(()-> "PlayNext: atad ref lost, cannot re-invoke native");
            }

            logDebug(() -> "PlayNext: old player preserved, polling for new track ready");
            pollForNewTrackReady(newExo);
            return true;

        } catch (Exception e) {
            logError(()-> "onBeforePlayNext error", e);
            resetCrossfade();
            return false;
        }
    }

    /**
     * Loop enum order: LOOP_OFF, LOOP_ALL, LOOP_ONE, LOOP_DISABLED.
     */
    private static final int LOOP_ONE_ORDINAL = 2;
    private static volatile boolean sleepTimerEndOfTrack = false;

    /**
     * Injection point.
     */
    public static void onSleepTimerStateChanged(Object state) {
        boolean endOfTrack = state instanceof Enum<?> e && "ACTIVE_END_OF_TRACK".equals(e.name());
        if (endOfTrack != sleepTimerEndOfTrack) {
            sleepTimerEndOfTrack = endOfTrack;
            logInfo(() -> "onSleepTimerStateChanged: endOfTrack=" + endOfTrack);
        }
    }

    private static volatile boolean repeatSingleActive = false;
    private static volatile Object lastLoadDescriptor = null;

    /**
     * Injection point.
     */
    public static void onLoopStateChanged(Object loopState) {
        try {
            boolean single = (loopState instanceof Enum)
                    && ((Enum<?>) loopState).ordinal() == LOOP_ONE_ORDINAL;
            if (single != repeatSingleActive) {
                repeatSingleActive = single;
                final int ord = (loopState instanceof Enum) ? ((Enum<?>) loopState).ordinal() : -1;
                logInfo(() -> "onLoopStateChanged: repeatSingleActive=" + single
                        + " (loop ordinal=" + ord + ")");
            }
        } catch (Exception e) {
            logWarn(() -> "onLoopStateChanged error: " + e.getMessage());
        }
    }

    /**
     * Injection point.
     */
    public static void onBeforeLoadVideo(Object newAtzqInstance, Object descriptor) {
        if (descriptor != null) {
            lastLoadDescriptor = descriptor;
        }
        // Content is on its way, so the idle recovery must not abandon a slow load.
        if (crossfadeInProgress) {
            pendingLoadIssued = true;
            ExoPlayerAccess outgoing = outgoingAwaitingLoad;
            if (outgoing != null) {
                outgoingAwaitingLoad = null;
                preStartOutgoingFadeOut(outgoing);
            }
        }
        logDebug(() -> "9.x: onBeforeLoadVideo atzq=@" + System.identityHashCode(newAtzqInstance)
                + " descriptor=@" + System.identityHashCode(descriptor)
                + " crossfadeInProgress=" + crossfadeInProgress
                + " autoAdvActive=" + autoAdvanceCrossfadeActive
                + " repeatSingle=" + repeatSingleActive);
    }

    private static long lastPauseEventMs = 0;
    private static long lastPlayEventMs = 0;
    private static final long EVENT_DEDUP_WINDOW_MS = 100;
    private static volatile long lastPauseVideoMs = System.currentTimeMillis();
    /**
     * A skip calls pauseVideo 10 to 50 ms before stopVideo, a user pause comes seconds earlier.
     */
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
                + " atad=" + (atadInstance != null)
                + " nbaAlive=" + (lastNbaRef != null && lastNbaRef.get() != null) + "]");

        // The toggle hook only catches manual toggles, not music videos that start in video mode.
        if (!isCrossfadePaused && isCurrentlyInVideoMode()) {
            logDebug(() -> "onPlayVideo: coercing video -> audio (crossfade active)");
            forceAudioModeIfNeeded();
        }

        if (!crossfadeInProgress) {
            logDebug(() -> "onPlayVideo: starting auto-advance monitor");
            startAutoAdvanceMonitor();
        } else {
            logDebug(() -> "onPlayVideo: crossfade in progress, skipping auto-advance monitor start");
        }
    }

    private static int lastPollState = -1;
    private static long pollIdleStreakStartMs = 0;
    /**
     * A slow load also sits in STATE_IDLE, much longer with the screen locked, so the idle
     * recovery only runs when no load was issued for the transition.
     */
    private static volatile boolean pendingLoadIssued = false;

    private static void pollForNewTrackReady(final ExoPlayerAccess newPlayer) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        lastPollState = -1;
        pollIdleStreakStartMs = 0;
        pendingLoadIssued = false;

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!crossfadeInProgress) return;
                if (newPlayer != pendingInPlayer) return;

                // The native playNextInQueue resets the volume to 1.0 after the hook.
                try { newPlayer.patch_setVolume(0.0f); } catch (Exception ignored) {}

                try {
                    int state = newPlayer.patch_getPlaybackState();
                    if (state == STATE_READY) {
                        logDebug(() -> "Pending track READY, promoting to crossfade");
                        onPendingPlayerReady(newPlayer);
                        return;
                    }

                    if (state == STATE_ENDED) {
                        logError(() -> "Pending player ENDED unexpectedly, aborting");
                        recoverFailedLoad();
                        return;
                    }

                    // A queue that was dismissed or ended never loads, so recover early
                    // instead of waiting for the full timeout (#1671).
                    if (state == STATE_IDLE && !pendingLoadIssued) {
                        if (pollIdleStreakStartMs == 0) {
                            pollIdleStreakStartMs = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - pollIdleStreakStartMs >= IDLE_LOAD_FAIL_MS) {
                            logInfo(() -> "Pending player stuck IDLE, queue dismissed/ended; "
                                    + "recovering " + dumpState());
                            recoverFailedLoad();
                            return;
                        }
                    } else {
                        pollIdleStreakStartMs = 0;
                    }

                    if (state != lastPollState) {
                        logDebug(() -> "Poll: state -> " + state);
                        lastPollState = state;
                    }

                    if (System.currentTimeMillis() > deadline) {
                        logError(() -> "Timeout waiting for new track");
                        recoverFailedLoad();
                        return;
                    }

                    mainHandler.postDelayed(this, READY_POLL_MS);
                } catch (Exception e) {
                    logError(()-> "Poll error", e);
                    recoverFailedLoad();
                }
            }
        }, READY_POLL_MS);
    }

    /**
     * On auto-advance the outgoing is close to its end, so it plays out instead of being cut
     * when the next track never loads (#2433). After a manual skip it would play on for minutes.
     */
    private static void recoverFailedLoad() {
        ExoPlayerAccess outgoing = pendingOutPlayer;
        if (outgoing != null && autoAdvanceCrossfadeActive) {
            pendingOutPlayer = null;
            removeFromFading(outgoing);
            try { outgoing.patch_setVolume(1.0f); } catch (Exception ignored) {}
            releaseWhenEnded(outgoing);
        }
        resetCrossfade();
    }

    private static void releaseWhenEnded(ExoPlayerAccess player) {
        long remaining = 0;
        try {
            remaining = Math.max(0, player.patch_getDuration() - player.patch_getCurrentPosition());
        } catch (Exception ignored) {}
        final long deadline = System.currentTimeMillis() + Math.min(remaining, READY_TIMEOUT_MS) + 1000;
        logInfo(() -> "Next track did not load, letting @" + System.identityHashCode(player) + " play out");
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isReady(player) && System.currentTimeMillis() < deadline) {
                    mainHandler.postDelayed(this, READY_POLL_MS);
                    return;
                }
                releasePlayer(player);
            }
        }, READY_POLL_MS);
    }

    /**
     * The pending player is kept: the coordinator already uses it, and releasing it leaves
     * the coordinator on a dead player until the app is killed (#1671).
     */
    private static void resetCrossfade() {
        resetCrossfade(false);
    }

    /**
     * @param stopKeptPlayer On a dismissal the kept player has to be paused, a failed load has nothing audible.
     */
    private static void resetCrossfade(boolean stopKeptPlayer) {
        cleanupAllPlayers(stopKeptPlayer);
        if (audioModeWasForced) {
            audioModeWasForced = false;
            restoreVideoModeSilently();
        }
    }

    /**
     * Injection point.
     */
    public static void onQueueDismissed() {
        if (!CROSSFADE_ENABLED) return;
        dismissWindowUntilMs = SystemClock.uptimeMillis() + DISMISS_WINDOW_MS;
        logInfo(() -> "onQueueDismissed, crossfade suppressed for the dismiss stop " + dumpState());
        if (crossfadeInProgress) {
            // The dismissal arrives as stopVideo(5), which advances instead of halting,
            // so the swapped in player keeps playing unless it is paused here (#1773).
            resetCrossfade(true);
        }
    }

    private static void onPendingPlayerReady(ExoPlayerAccess newPlayer) {
        FadeCurve curve = Settings.CROSSFADE_CURVE.get();
        long fadeDuration = getCrossfadeDurationMs();

        boolean trackAlreadyEnded = false;
        ExoPlayerAccess outgoing = pendingOutPlayer;
        if (outgoing != null) {
            logDebug(() -> "onPendingPlayerReady (9.x): coordinator listener already migrated at start");

            if (outgoingFadePreStarted) {
                logDebug(() -> "onPendingPlayerReady: outgoing @" + System.identityHashCode(outgoing)
                        + " fade-out already in flight (pre-started at swap time)");
                pendingOutPlayer = null;
                outgoingFadePreStarted = false;
            } else {
                // Otherwise the outgoing track can end before its volume reaches zero.
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
                            logDebug(() -> "Outgoing track ended before READY, "
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
                            + " -> released (track ended before READY)");
                } else {
                    fadingOutPlayers.add(new FadingPlayer(outgoing, fadeOutDuration, curve));
                    final long fadeOutDurationFinal = fadeOutDuration;
                    logDebug(() -> "Original outgoing player @" + System.identityHashCode(outgoing)
                            + " -> fade-out list (" + fadeOutDurationFinal + "ms)");
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
                        + " -> quick fade-out from " + String.format(Locale.US, "%.2f", vol)
                        + " over " + quickDuration + "ms");
            } else {
                releasePlayer(prevIncoming);
                logDebug(() -> "Previous incoming player @"
                        + System.identityHashCode(prevIncoming)
                        + " -> released (vol ~ 0)");
            }
        }

        crossfadeInPlayer = newPlayer;
        pendingInPlayer = null;
        currentFadeInVolume = 0.0f;

        ensureFadingLoopRunning();
        // With the old track already over there is nothing to overlap, so a slow fade would just be silence.
        animateCrossfade(newPlayer, trackAlreadyEnded ? QUICK_FADE_MS : 0);
    }

    private static void startAutoAdvanceMonitor() {
        stopAutoAdvanceMonitor();
        if (!CROSSFADE_ENABLED || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            return;
        }
        if (autoAdvanceCrossfadeActive) {
            logDebug(() -> "startAutoAdvanceMonitor: skipped, 9.x volume-fade in progress");
            return;
        }

        autoAdvanceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (isCrossfadePaused
                        || !Settings.CROSSFADE_ON_AUTO_ADVANCE.get()
                        || crossfadeInProgress
                        || autoAdvanceCrossfadeActive) {
                    return;
                }

                // Keeps polling, so it resumes once casting stops (#1549), the end of song
                // sleep timer is cleared (#2017) or the stream is no longer SABR (#2748).
                if (isAudioRoutedToCast() || sleepTimerEndOfTrack || isSabrStream()) {
                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                    return;
                }

                Object atad = lastAtadRef.get();
                if (atad == null) {
                    mainHandler.postDelayed(this, MONITOR_POLL_MS);
                    return;
                }

                try {
                    PlayerCoordinatorAccess coordinator = getCoordinatorFromAtad(atad, false);
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

                        // MEDIA_NEXT ignores repeat-one. If the self crossfade cannot start,
                        // MEDIA_NEXT still loops the song, only without a crossfade.
                        if (repeatSingleActive) {
                            logInfo(() -> "Auto-advance: REPEAT_SINGLE, crossfading song onto itself");
                            if (startRepeatSelfCrossfade(atad, coordinator, exo)) {
                                return;
                            }
                            logWarn(() -> "REPEAT_SINGLE self-crossfade could not engage, falling back to MEDIA_NEXT");
                        }

                        // Calling playNextInQueue or stopVideo(5) directly does not advance
                        // the queue on 9.x, only the MediaSession skip handler does.
                        logDebug(() -> "Auto-advance: TRIGGER FIRED"
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
                            logWarn(()-> "Auto-advance: no Context, cannot dispatch MEDIA_NEXT");
                            return;
                        }
                        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                        if (am == null) {
                            monitorTriggeredSkip = false;
                            queueAdvancedByMonitor = false;
                            logWarn(()-> "Auto-advance: no AudioManager, cannot dispatch MEDIA_NEXT");
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

    /**
     * Same swap as an auto-advance, but the current track is reloaded instead of advancing
     * the queue. Returns false when it cannot start, so the caller falls back to MEDIA_NEXT.
     */
    private static boolean startRepeatSelfCrossfade(Object atad,
            PlayerCoordinatorAccess coordinator, ExoPlayerAccess currentExo) {
        final Object descriptor = lastLoadDescriptor;
        if (descriptor == null) {
            logWarn(() -> "repeat-single: no cached load descriptor");
            return false;
        }
        if (!(atad instanceof MedialibPlayerAccess)) {
            logWarn(() -> "repeat-single: atad is not a MedialibPlayerAccess");
            return false;
        }
        try {
            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                logError(() -> "repeat-single: factory failed to create player");
                return false;
            }
            beginCrossfade(coordinator, currentExo, newExo);
            // The outgoing ends during the fade like on auto-advance, but the queue does not advance.
            autoAdvanceCrossfadeActive = true;
            detachCwh(currentExo);
            swapCoordinatorPlayer(coordinator, currentExo, newExo);

            currentExo.patch_setPlayWhenReady(true);
            currentExo.patch_setVolume(1.0f);
            outgoingAwaitingLoad = currentExo;

            logInfo(() -> "repeat-single: re-issuing loadVideo (same song) onto @"
                    + System.identityHashCode(newExo) + " descriptor=@"
                    + System.identityHashCode(descriptor));
            ((MedialibPlayerAccess) atad).patch_loadVideoWith(descriptor);

            pollForNewTrackReady(newExo);
            return true;
        } catch (Exception e) {
            logError(() -> "startRepeatSelfCrossfade error", e);
            cleanupAllPlayers();
            // State was already changed, so MEDIA_NEXT must not fire as well.
            return true;
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
        if (isReady(pending)) {
            bestPlayer = pending;
        } else if (isReady(inp)) {
            bestPlayer = inp;
        } else {
            bestPlayer = pendOut;
        }

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

        removeFromFading(pendOut);
        if (inp != null && inp != bestPlayer) releasePlayer(inp);
        if (pending != null && pending != bestPlayer) releasePlayer(pending);
        if (pendOut != null && pendOut != bestPlayer) releasePlayer(pendOut);

        releaseAllFadingPlayers();
        clearCrossfadeState();

        if (audioModeWasForced) {
            audioModeWasForced = false;
            restoreVideoModeSilently();
        }
    }

    private static boolean isReady(ExoPlayerAccess player) {
        if (player == null) return false;
        try {
            return player.patch_getPlaybackState() == STATE_READY;
        } catch (Exception e) {
            return false;
        }
    }

    private static void clearCrossfadeState() {
        crossfadeInPlayer = null;
        pendingInPlayer = null;
        pendingOutPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        autoAdvanceCrossfadeActive = false;
        queueAdvancedByMonitor = false;
        outgoingFadePreStarted = false;
        outgoingAwaitingLoad = null;
        currentFadeInVolume = 0.0f;
    }

    private static void animateCrossfade(final ExoPlayerAccess inPlayer, final long durationOverrideMs) {
        // The native playNextInQueue runs after the hook and can reset the volume to 1.0.
        try {
            inPlayer.patch_setVolume(0.0f);
        } catch (Exception e) {
            logWarn(()-> "fade-in pre-start: failed to zero volume: " + e.getMessage());
        }

        try { inPlayer.patch_setPlayWhenReady(true); } catch (Exception ignored) {}

        // The incoming starts without playVideo, so onPlayVideo never resets this. A stale false
        // after a swipe dismissal silences the next outgoing and leaves a gap.
        playerIsPlaying = true;

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
                        clearCrossfadeState();

                        // Video mode is not restored: the loaded stream is audio only and the
                        // video view would stay black. Pausing crossfade restores it.
                        audioModeWasForced = false;

                        startAutoAdvanceMonitor();
                    } else {
                        logDebug(() -> "Fade-in complete but pending player exists, "
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

    private static PlayerCoordinatorAccess getCoordinatorFromAtad(Object atadInstance) {
        return getCoordinatorFromAtad(atadInstance, true);
    }

    /**
     * @param log False for the monitor, which calls this on every poll.
     */
    private static PlayerCoordinatorAccess getCoordinatorFromAtad(Object atadInstance, boolean log) {
        try {
            Object chain = ((MedialibPlayerAccess) atadInstance).patch_getPlayerChain();
            if (chain == null) {
                if (log) logError(() -> "atad player chain is null");
                return null;
            }

            int depth = 0;
            while (chain instanceof DelegateAccess) {
                Object delegate = ((DelegateAccess) chain).patch_getDelegate();
                if (delegate == null || delegate == chain) break;
                chain = delegate;
                depth++;
            }

            if (chain instanceof PlayerCoordinatorAccess coordinator) {
                if (log) {
                    final int depthFinal = depth;
                    logDebug(() -> "Traversed " + depthFinal + " delegates -> "
                            + coordinator.getClass().getName());
                }
                return coordinator;
            }
            if (log) {
                Object chainFinal = chain;
                logError(() -> "Innermost class is not a PlayerCoordinatorAccess: "
                        + chainFinal.getClass().getName());
            }
            return null;
        } catch (Exception e) {
            if (log) logError(() -> "getCoordinatorFromAtad error", e);
            return null;
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

        // Done only now, so the outgoing keeps reporting pause and seek events during the fade.
        try {
            p.patch_detachCwhFromEventDispatch();
            logDebug(() -> "releasePlayer: 9.x detached cwh from event dispatch on @"
                    + System.identityHashCode(p));
        } catch (Exception e) {
            logDebug(() -> "releasePlayer: 9.x cwh event dispatch detach failed: " + e.getMessage());
        }

        suppressCwhU = true;
        try {
            p.patch_release();
        } catch (Exception e) {
            logDebug(() -> "releasePlayer: release() threw: " + e.getMessage());
        } finally {
            suppressCwhU = false;
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

    /**
     * The outgoing can be both pending and fading, and must be released only once.
     */
    private static void removeFromFading(ExoPlayerAccess player) {
        if (player == null) return;
        synchronized (fadingOutPlayers) {
            fadingOutPlayers.removeIf(fp -> fp.player == player);
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
        cleanupAllPlayers(false);
    }

    /**
     * @param stopKeptPlayer Pauses the kept player, so a dismissed track stops. Other callers
     *        leave it ready for the next load.
     */
    private static void cleanupAllPlayers(boolean stopKeptPlayer) {
        // Not logError: cleanup is routine on every dismissal, and logError shows an error toast.
        logInfo(() -> "CLEANUP (reset crossfade state): " + dumpState());
        removeFromFading(pendingOutPlayer);
        releaseAllFadingPlayers();
        // The coordinator already uses this player, so it is kept (#1671). Its volume was
        // zeroed for the fade, and left at 0 the next track would play silently.
        ExoPlayerAccess pin = pendingInPlayer;
        if (pin != null) {
            try {
                pin.patch_setVolume(1.0f);
                if (stopKeptPlayer) {
                    pin.patch_setPlayWhenReady(false);
                    logInfo(() -> "cleanupAllPlayers: paused kept coordinator player @"
                            + System.identityHashCode(pin) + " (dismiss, stop dismissed track)");
                } else {
                    logInfo(() -> "cleanupAllPlayers: restored kept coordinator player @"
                            + System.identityHashCode(pin) + " volume -> 1.0 (#1671 silent-playback guard)");
                }
            } catch (Exception ignored) {}
        }
        pendingInPlayer = null;
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) { releasePlayer(po); pendingOutPlayer = null; }
        // Also owned by the coordinator, and mid fade-in its volume can still be below 1.0.
        ExoPlayerAccess cip = crossfadeInPlayer;
        if (cip != null && cip != pin) {
            try {
                cip.patch_setVolume(1.0f);
                if (stopKeptPlayer) cip.patch_setPlayWhenReady(false);
            } catch (Exception ignored) {}
        }
        if (autoAdvanceCrossfadeActive) {
            logWarn(()-> "cleanupAllPlayers: clearing autoAdvanceCrossfadeActive mid-fade " + dumpState());
        }
        clearCrossfadeState();
    }

    private static void ensureFadingLoopRunning() {
        if (fadingLoopRunning) return;
        if (fadingOutPlayers.isEmpty()) return;
        fadingLoopRunning = true;
        mainHandler.post(CrossfadePatch::tickFadingLoop);
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
            mainHandler.postDelayed(CrossfadePatch::tickFadingLoop, TICK_MS);
        } else {
            fadingLoopRunning = false;
            logDebug(() -> "Fading loop stopped, all fade-outs complete");
        }
    }

    /**
     * Injection point. Playback goes on in the foreground service, so neither the monitor
     * nor a running crossfade is stopped here (#1311, #1442).
     */
    public static void onActivityStop() {
        activityRunning = false;
        logInfo(() -> "onActivityStop");
    }

    /**
     * Injection point. crossfadeInPlayer is not released, the coordinator owns it across
     * activity recreation.
     */
    public static void onActivityDestroy() {
        activityRunning = false;
        if (!crossfadeInProgress
                && pendingInPlayer == null
                && pendingOutPlayer == null
                && fadingOutPlayers.isEmpty()) {
            logInfo(() -> "onActivityDestroy, no in-flight crossfade state to release");
            return;
        }
        logInfo(() -> "onActivityDestroy, releasing in-flight crossfade state " + dumpState());
        stopAutoAdvanceMonitor();
        removeFromFading(pendingOutPlayer);
        releaseAllFadingPlayers();
        ExoPlayerAccess pi = pendingInPlayer;
        if (pi != null) { releasePlayer(pi); pendingInPlayer = null; }
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) { releasePlayer(po); pendingOutPlayer = null; }
        clearCrossfadeState();
        monitorTriggeredSkip = false;
    }

    /**
     * Injection point.
     */
    public static void onActivityStart() {
        activityRunning = true;
        logInfo(() -> "onActivityStart");

        // Pausing is per session, and the process is not always killed on swipe.
        if (isCrossfadePaused) {
            logDebug(() -> "onActivityStart: auto-resetting isCrossfadePaused to false");
            isCrossfadePaused = false;
        }

        // A recreated activity otherwise has no handler until another hook runs.
        tryAttachLongPressHandler();

        startAutoAdvanceMonitor();
    }

    private static volatile long lastCastCheckMs = 0;
    private static volatile boolean lastCastResult = false;
    private static final long CAST_CHECK_TTL_MS = 250;
    /**
     * Shown once per process, not on every track change while casting.
     */
    private static volatile boolean castUnstableToastShown = false;

    /**
     * The player swap flickers the MediaSession state, which receivers get as pause and
     * play commands, and strict ones drop the connection (#1549).
     */
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
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
                // A remote route decodes on the receiver, which is what breaks. Local decoding
                // (speaker, Bluetooth, wired, USB) is fine. MediaRouter2 is not on the classpath.
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
                // MediaRouter does not reflect every remote output.
                if (!casting) {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        // noinspection WrongConstant
                        for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                            int type = info.getType();
                            probe.append("dev{type=").append(type)
                                    .append(",id=").append(info.getId()).append("} ");
                            // No TYPE_REMOTE_SUBMIX: Android Auto and screen recording use it but
                            // decode locally. TODO: this also lets Samsung mirroring (#1549) through.
                            if (type == AudioDeviceInfo.TYPE_HDMI
                                    || type == AudioDeviceInfo.TYPE_HDMI_ARC
                                    || type == 29 /* TYPE_HDMI_EARC, API 31+ */
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
                    + ", crossfade " + (castingFinal ? "disabled" : "re-enabled")
                    + " [" + probe.toString().trim() + "]");
            // Otherwise crossfade would stop working with no visible reason.
            if (casting && !castUnstableToastShown) {
                castUnstableToastShown = true;
                try {
                    Utils.showToastShort(str("morphe_music_crossfade_cast_unstable_toast"));
                } catch (Exception ignored) {}
            }
        }

        lastCastResult = casting;
        return casting;
    }

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    @SuppressLint("MissingPermission")
    private static synchronized void toggleSessionPause() {
        isCrossfadePaused = !isCrossfadePaused;
        boolean isPaused = isCrossfadePaused;

        logDebug(() -> "Session " + (isPaused ? "PAUSED" : "RESUMED")
                + " [inVideo=" + isCurrentlyInVideoMode()
                + " inProgress=" + crossfadeInProgress + "]");

        if (isCrossfadePaused) {
            // Skips the silent video restore in abortCrossfadeNow, the user switches back with the toggle.
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
        if (!CROSSFADE_ENABLED) return false;
        lastNbaRef = new WeakReference<>(nba);
        tryAttachLongPressHandler();
        try {
            VideoToggleAccess toggle = (VideoToggleAccess) nba;
            boolean isAudioMode = toggle.patch_isAudioMode();

            logDebug(() -> "videoToggle: isAudioMode=" + isAudioMode
                    + " paused=" + isCrossfadePaused
                    + " inVideoMode(before)=" + inVideoMode);

            // Silent audio switches left subscribers out of sync, so the native toggle to
            // video can be a no-op for them and has to be replaced by a broadcast.
            if (isCrossfadePaused) {
                if (isAudioMode) {
                    try {
                        ((VideoToggleAccess) nba).patch_restoreVideoMode();
                        inVideoMode = true;
                        audioModeWasForced = false;
                        manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
                        logDebug(() -> "videoToggle -> INTERCEPTED (audio->video while paused), applied broadcast restoreVideoMode");
                        return true;
                    } catch (Exception e) {
                        logWarn(()-> "videoToggle intercept failed, allowing natural toggle: " + e.getMessage());
                        return false;
                    }
                }
                manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
                logDebug(() -> "videoToggle -> ALLOW (video->audio while paused)");
                return false;
            }

            if (isAudioMode) {
                logDebug(() -> "videoToggle -> BLOCK (audio->video while crossfade active)");
                Utils.showToastShort(str("morphe_music_crossfade_video_mode_disabled_toast"));
                return true;
            }

            inVideoMode = false;
            manualToggleSuppressionUntil = System.currentTimeMillis() + 500;
            logDebug(() -> "videoToggle -> ALLOW (video->audio, suppressing crossfade for 500ms)");
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
        logWarn(()-> "findNbaInChain: nba not found in delegate chain, audio/video mode unknown");
        return null;
    }

    private static void forceAudioModeIfNeeded() {
        Object nba = lastNbaRef.get();
        if (nba == null) {
            nba = findNbaInChain();
        }
        if (nba == null) {
            logWarn(()-> "forceAudioModeIfNeeded: nba not found, cannot force audio mode. "
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
        logWarn(()-> "isCurrentlyInVideoMode: nba not in chain, returning cached inVideoMode=" + inVideoMode
                + " (may be stale)");
        return inVideoMode;
    }

    private static int getCrossfadeDurationMs() {
        return Settings.CROSSFADE_DURATION.get().milliseconds;
    }

    private static final String[] SHUFFLE_IDS = {
            "queue_shuffle_button",
            "queue_shuffle",
            "playback_queue_shuffle_button_view",
            "overlay_queue_shuffle_button_view"
    };

    private static android.view.ViewTreeObserver.OnGlobalLayoutListener longPressLayoutListener;
    private static WeakReference<View> longPressLayoutListenerHost = new WeakReference<>(null);

    /**
     * The hooks that attach the handler fire in quick bursts.
     */
    private static volatile boolean pendingLongPressAttach = false;

    private static void tryAttachLongPressHandler() {
        if (!CROSSFADE_ENABLED || !Settings.CROSSFADE_SESSION_CONTROL.get()) return;
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
                @SuppressLint("DiscouragedApi")
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

            StringBuilder attachLog = new StringBuilder("Long-press attach: matched=" + matchedIds + ", attaching to:");
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

    /**
     * Re-attaches on every layout and never removes itself, because the UI can rebind
     * the button's handlers at any time.
     */
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
            longPressLayoutListener = CrossfadePatch::tryAttachLongPressNow;
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

        // A long click listener survives the app rebinding its touch handler and keeps the normal tap.
        btn.setOnLongClickListener(v -> {
            toggleSessionPause();
            logDebug(() -> "Shuffle long-press fired on " + tag + "@" + viewId);
            return true;
        });
        btn.setLongClickable(true);
    }

}

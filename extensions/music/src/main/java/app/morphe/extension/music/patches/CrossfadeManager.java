package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
        /** Calls the coordinator's internal player-transition method (listener migration + field write). */
        void patch_setPlayerWithBindings(Object player);
        Object patch_getSession();
        Object patch_getLoadControl();
        Object patch_getSharedState();
        Object patch_getSharedCallback();
        Object patch_getVideoSurface();
        /**
         * Returns the coordinator's Player.Listener (b field, type Lcou).
         * This listener is registered into ExoPlayer's direct N set (Lcrh.N)
         * via O(Lcou;)V, NOT via the cau ListenerHolderSet.
         * 9.x only — 8.x bridge is not injected.
         */
        Object patch_getCoordinatorListener();
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
        /** Registers a raw Player.Listener on this player via ExoPlayer's addListener. */
        void patch_addListener(Object listener);
        /**
         * Adds a listener directly to this player's N set (Lcrh.N —
         * the direct CopyOnWriteArraySet, NOT the cau ListenerHolderSet).
         * 9.x only — 8.x bridge is not injected.
         */
        void patch_addDirectListener(Object listener);
        /**
         * Removes a listener from this player's N set (Lcrh.N).
         * 9.x only — 8.x bridge is not injected.
         */
        void patch_removeDirectListener(Object listener);
        /**
         * Removes coordinator_cwh from this player's crh.h:Lcgd event dispatch set.
         * Must be called on the OUTGOING player before release so its release-time
         * isPlayingChanged(false) does not propagate through cwh.b to MediaSession.
         * 9.x only — 8.x bridge is not injected.
         */
        void patch_detachCwhFromEventDispatch();
        /**
         * Returns the size of this player's direct N set (Lcrh.N).
         * Diagnostic only — lets us verify patch_addDirectListener actually registered the listener.
         * 9.x only — 8.x bridge is not injected.
         */
        int patch_getDirectListenerCount();
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
        Logger.printInfo(() -> msg);
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
    private static volatile boolean activityRunning = false;

    /**
     * Set at patch time via sput-boolean — true when running on YTM 9.x.
     * On 9.x, blocking stopVideo also blocks playVideo (same call chain),
     * so we use a deferred coordinator swap instead of blocking native.
     */
    public static volatile boolean is9x = false;

    /**
     * True when we have set up crossfade state but deliberately NOT swapped
     * the coordinator yet (9.x path). The swap is deferred until onPlayVideo
     * fires (or the postDelayed fallback runs after the native cycle completes).
     */
    private static volatile boolean deferredSwapPending = false;

    /**
     * Fallback Runnable for the 9.x deferred swap.
     * Scheduled at DEFERRED_SWAP_DELAY_MS after allowing native stopVideo to proceed.
     * Cancelled if onPlayVideo fires first, or if the crossfade is aborted.
     */
    private static Runnable deferredSwapRunnable = null;

    /**
     * How long to wait after allowing native stopVideo before executing the deferred
     * coordinator swap (9.x path). The native stopVideo→loadVideo→playVideo cycle
     * typically completes in ~250ms. 500ms is conservative.
     */
    private static final long DEFERRED_SWAP_DELAY_MS = 500;

    /**
     * Wall-clock time when deferredSwapPending was set to true.
     * Used to distinguish the 9.x-internal second stopVideo(5) call (arrives ~1ms
     * after the first) from a genuine user double-skip (arrives 200ms+).
     */
    private static volatile long deferredSwapStartTime = 0L;

    /**
     * Any second REASON_DIRECTOR_RESET that arrives within this window of
     * deferredSwapStartTime is treated as the 9.x-internal double-call and
     * allowed through without cancelling the deferred swap.
     */
    private static final long INTERNAL_CALL_WINDOW_MS = 100L;

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

    /**
     * The coordinator's UI listener (bxi) identified on the first successful
     * {@link #migrateListeners} call by eliminating factory-registered listeners.
     *
     * <p>Factory listeners whose bxi is shared (static) across ExoPlayer instances are
     * filtered via {@code alreadyPresent} identity check.  However, some factory
     * listeners have a fresh bxi instance per ExoPlayer — these are NOT identity-equal
     * to the new player's factory cats and would incorrectly pass the filter.
     *
     * <p>Once we've identified the real coordinator listener on skip 1, we record it here
     * and on all subsequent skips only migrate that exact object, ignoring per-player
     * factory variants regardless of whether they pass the identity check.</p>
     */
    private static volatile Object coordinatorListenerBxi = null;

    private static final java.util.List<FadingPlayer> fadingOutPlayers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static volatile boolean fadingLoopRunning = false;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static WeakReference<Object> lastNbaRef = new WeakReference<>(null);
    private static volatile boolean internalToggle = false;
    private static volatile boolean internalPlayNext = false;
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
            if (is9x) {
                // On 9.x the native stopVideo(5) body calls stopVideo(1) (and possibly
                // other reasons) as part of the stopVideo→loadVideo→playVideo chain that
                // loads the next track and connects the UI. We MUST allow these through
                // so loadVideo and playVideo complete on the new player in the coordinator.
                // The old player is kept separately as pendingOutPlayer and is unaffected.
                logDebug("stopVideo(" + reason + "): ALLOW — 9.x native cycle (crossfade in progress)");
                return false;
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
            if (crossfadeInProgress) cleanupAllPlayers();
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
                    + " wasInVideo=" + wasInVideoMode
                    + " is9x=" + is9x + "]");

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

            if (is9x) {
                pendingOutPlayer = currentExo;
                pendingInPlayer = newExo;
                activeCoordinator = coordinator;
                crossfadeInProgress = true;
                deferredSwapStartTime = System.currentTimeMillis(); // gates internal stopVideo(5) detection

                // Pre-remove the coordinator's listener (Lcou) from the outgoing player's direct
                // listener set (Lcrh.N) BEFORE calling patch_setPlayerWithBindings.
                // On skip 2+, the outgoing player is a factory player. Without this, the transition
                // method's internal stop of the factory player fires STOPPAGE_REASON_UNKNOWN via
                // Lcou (still registered in the factory player's Lcrh.N), triggering a premature
                // clearQueue → state machine corruption → onPlaying() never fires.
                Object coordListener = null;
                try {
                    coordListener = coordinator.patch_getCoordinatorListener();
                    if (coordListener != null) {
                        currentExo.patch_removeDirectListener(coordListener);
                        logInfo("9.x: pre-removed coord listener from outgoing @"
                                + System.identityHashCode(currentExo));
                    }
                } catch (Exception e) {
                    logWarn("9.x: pre-remove coord listener failed: " + e.getMessage());
                }

                // Detach coordinator_cwh from outgoing player's crh.h:Lcgd BEFORE the swap.
                // When released later, the outgoing player fires isPlayingChanged(false) through
                // crh.h → cwh.b → MediaSession. The boolean is captured at source and not
                // re-queried, so even though cwh.g = new player (playing), MediaSession shows
                // PAUSED. Removing cwh from crh.h silences all future events from this player.
                try {
                    currentExo.patch_detachCwhFromEventDispatch();
                    logInfo("9.x: detached cwh from event dispatch on outgoing @"
                            + System.identityHashCode(currentExo));
                } catch (Exception e) {
                    logWarn("9.x: cwh event dispatch detach failed: " + e.getMessage());
                }

                coordinator.patch_setPlayerWithBindings(newExo);
                logInfo("9.x: swapped coordinator → new player @" + System.identityHashCode(newExo)
                        + " via patch_setPlayerWithBindings (Lcou backref updated)");

                // Re-register Lcou into the new player's Lcrh.N.
                // patch_setPlayerWithBindings (the coordinator's transition method) only migrates
                // cau-level listeners — it never touches Lcrh.N. Without this, Lcou is in neither
                // player's Lcrh.N and MediaSession never receives onIsPlayingChanged(true).
                if (coordListener != null) {
                    try {
                        int lnBefore = newExo.patch_getDirectListenerCount();
                        newExo.patch_addDirectListener(coordListener);
                        int lnAfter = newExo.patch_getDirectListenerCount();
                        logInfo("9.x: Lcrh.N on newExo @" + System.identityHashCode(newExo)
                                + ": before=" + lnBefore + " after=" + lnAfter
                                + (lnAfter > lnBefore ? " ✓ Lcou registered" : " ✗ count unchanged — add may have failed"));
                    } catch (Exception e) {
                        logWarn("9.x: re-register coord listener failed: " + e.getMessage());
                    }
                }
                // Diagnostic: confirm coordinator is pointing to newExo after swap.
                try {
                    Object coordNow = coordinator.patch_getExoPlayer();
                    logInfo("9.x: coordinator.exoPlayer=@" + System.identityHashCode(coordNow)
                            + " newExo=@" + System.identityHashCode(newExo)
                            + (coordNow == newExo ? " ✓ match" : " ✗ MISMATCH"));
                } catch (Exception e) {
                    logWarn("9.x: coord identity check failed: " + e.getMessage());
                }

                VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
                if (surface != null) {
                    surface.patch_setPlayerReference(newExo);
                }

                // Re-enable the outgoing player for audible fade-out.
                try {
                    currentExo.patch_setPlayWhenReady(true);
                    currentExo.patch_setVolume(1.0f);
                    logInfo("9.x: re-enabled outgoing player @" + System.identityHashCode(currentExo));
                } catch (Exception e) {
                    logWarn("9.x: could not re-enable outgoing player: " + e.getMessage());
                }

                // Clear the outgoing player's cau ListenerHolderSet so STATE_IDLE/ENDED at
                // release time does not overwrite MediaSession state.
                detachPlayerListeners(currentExo);

                pollForNewTrackReady(newExo);
                return false; // Allow native stopVideo chain → loads track onto newExo
            } else {
                // 8.x path: block native, swap coordinator immediately so loadVideo
                // routes content onto the new player.
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
            }

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

        if (is9x) {
            long elapsed = System.currentTimeMillis() - deferredSwapStartTime;
            if (elapsed < INTERNAL_CALL_WINDOW_MS) {
                // This is the 9.x-internal second stopVideo(5) that always fires ~1ms
                // after the first as part of the native track-transition sequence.
                // It is NOT a user double-skip — pass it through untouched.
                logDebug("9.x: internal second stopVideo(5) after " + elapsed
                        + "ms — allowing through");
                return false;
            }
        }

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

            // Save and clear pendingInPlayer before factory call.
            ExoPlayerAccess oldPending = pendingInPlayer;
            pendingInPlayer = null;

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                logError("Chained skip: factory failed — aborting crossfade");
                // Clean up old pending before aborting.
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

            // Transition coordinator BEFORE releasing oldPending.
            // coordinator.exoPlayer currently points to oldPending (set during first skip).
            Object chainedCoordListener = null;
            if (is9x) {
                // Pre-remove coord listener from the outgoing player (coordinator's current player
                // = oldPending = the first skip's factory player) to prevent premature clearQueue.
                try {
                    chainedCoordListener = coordinator.patch_getCoordinatorListener();
                    if (chainedCoordListener != null && oldPending != null) {
                        oldPending.patch_removeDirectListener(chainedCoordListener);
                        logInfo("9.x chained: pre-removed coord listener from @"
                                + System.identityHashCode(oldPending));
                    }
                } catch (Exception e) {
                    logWarn("9.x chained: pre-remove coord listener failed: " + e.getMessage());
                }
            }
            if (is9x && oldPending != null) {
                try {
                    oldPending.patch_detachCwhFromEventDispatch();
                    logInfo("9.x chained: detached cwh from event dispatch on outgoing @"
                            + System.identityHashCode(oldPending));
                } catch (Exception e) {
                    logWarn("9.x chained: cwh event dispatch detach failed: " + e.getMessage());
                }
            }
            coordinator.patch_setPlayerWithBindings(newExo);
            logInfo("Chained skip: swapped coordinator → new player @"
                    + System.identityHashCode(newExo));

            // Re-register Lcou into new player's Lcrh.N (9.x only).
            if (is9x && chainedCoordListener != null) {
                try {
                    int lnBefore = newExo.patch_getDirectListenerCount();
                    newExo.patch_addDirectListener(chainedCoordListener);
                    int lnAfter = newExo.patch_getDirectListenerCount();
                    logInfo("9.x chained: Lcrh.N on newExo @" + System.identityHashCode(newExo)
                            + ": before=" + lnBefore + " after=" + lnAfter
                            + (lnAfter > lnBefore ? " ✓ Lcou registered" : " ✗ count unchanged"));
                } catch (Exception e) {
                    logWarn("9.x chained: re-register coord listener failed: " + e.getMessage());
                }
            }
            if (is9x) {
                try {
                    Object coordNow = coordinator.patch_getExoPlayer();
                    logInfo("9.x chained: coordinator.exoPlayer=@" + System.identityHashCode(coordNow)
                            + " newExo=@" + System.identityHashCode(newExo)
                            + (coordNow == newExo ? " ✓ match" : " ✗ MISMATCH"));
                } catch (Exception e) {
                    logWarn("9.x chained: coord identity check failed: " + e.getMessage());
                }
            }

            // Now release old pending.
            if (oldPending != null) {
                logInfo("Chained skip: releasing old pending @"
                        + System.identityHashCode(oldPending)
                        + " (never reached READY)");
                if (is9x) detachPlayerListeners(oldPending); // clear cau listeners
                releasePlayer(oldPending);
            }

            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
            }

            pollForNewTrackReady(newExo);

            return !is9x; // 8.x: block native stopVideo; 9.x: allow native chain to load track onto newExo
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
                if (!is9x) {
                    logError("Factory failed to set timeline — aborting");
                    sharedState.patch_setTimeline(oldTimeline);
                    sharedCallback.patch_setCqb(oldCqb);
                    return null;
                }
                // On 9.x the timeline field is final; the factory cannot re-set it.
                // Restore the old value so the shared state remains coherent.
                logWarn("Factory did not re-set timeline (expected on 9.x — field is final, restoring)");
                sharedState.patch_setTimeline(oldTimeline);
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

    /**
     * Returns true to BLOCK the native playNextInQueue, false to allow it.
     *
     * Strategy: we block the original call, set up our crossfade state, then
     * invoke playNextInQueue again via patch_playNextInQueue with internalPlayNext=true.
     * That second call passes through immediately (returns false), allowing the native
     * to load the next track onto our new player. We then synchronously re-enforce
     * volume=0 right after the native returns — eliminating the blip that occurred
     * in the void-hook design where the native ran in the 100ms poll window.
     */
    public static boolean onBeforePlayNext(Object coordinatorInstance) {
        // Internal re-invoke: let native through immediately.
        if (internalPlayNext) {
            internalPlayNext = false;
            return false;
        }

        logInfo("onBeforePlayNext called");
        tryAttachLongPressHandler();

        if (!isEnabled() || sessionPaused || getCrossfadeDurationMs() <= 0
                || crossfadeInProgress) {
            return false;
        }

        if (!Settings.CROSSFADE_ON_AUTO_ADVANCE.get()) {
            logDebug("PlayNext: skip — auto-advance crossfade disabled");
            return false;
        }

        try {
            boolean wasInVideoMode = isCurrentlyInVideoMode();

            PlayerCoordinatorAccess coordinator =
                    (PlayerCoordinatorAccess) coordinatorInstance;

            ExoPlayerAccess currentExo = (ExoPlayerAccess) coordinator.patch_getExoPlayer();
            if (currentExo == null) return false;

            int currentState = currentExo.patch_getPlaybackState();
            logDebug("PlayNext: current player state=" + currentState
                    + " wasInVideo=" + wasInVideoMode);

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) return false;

            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;
            deferredSwapStartTime = System.currentTimeMillis(); // gate 9.x internal stopVideo(5)

            Object playNextCoordListener = null;
            if (is9x) {
                // Pre-remove coord listener from outgoing player before transition.
                try {
                    playNextCoordListener = coordinator.patch_getCoordinatorListener();
                    if (playNextCoordListener != null) {
                        currentExo.patch_removeDirectListener(playNextCoordListener);
                        logInfo("9.x PlayNext: pre-removed coord listener from @"
                                + System.identityHashCode(currentExo));
                    }
                } catch (Exception e) {
                    logWarn("9.x PlayNext: pre-remove coord listener failed: " + e.getMessage());
                }
            }
            if (is9x) {
                try {
                    currentExo.patch_detachCwhFromEventDispatch();
                    logInfo("9.x PlayNext: detached cwh from event dispatch on outgoing @"
                            + System.identityHashCode(currentExo));
                } catch (Exception e) {
                    logWarn("9.x PlayNext: cwh event dispatch detach failed: " + e.getMessage());
                }
            }
            coordinator.patch_setPlayerWithBindings(newExo);
            logInfo("PlayNext: swapped coordinator → new player @"
                    + System.identityHashCode(newExo));

            // Re-register Lcou into new player's Lcrh.N (9.x only).
            if (is9x && playNextCoordListener != null) {
                try {
                    int lnBefore = newExo.patch_getDirectListenerCount();
                    newExo.patch_addDirectListener(playNextCoordListener);
                    int lnAfter = newExo.patch_getDirectListenerCount();
                    logInfo("9.x PlayNext: Lcrh.N on newExo @" + System.identityHashCode(newExo)
                            + ": before=" + lnBefore + " after=" + lnAfter
                            + (lnAfter > lnBefore ? " ✓ Lcou registered" : " ✗ count unchanged"));
                } catch (Exception e) {
                    logWarn("9.x PlayNext: re-register coord listener failed: " + e.getMessage());
                }
            }
            if (is9x) {
                try {
                    Object coordNow = coordinator.patch_getExoPlayer();
                    logInfo("9.x PlayNext: coordinator.exoPlayer=@" + System.identityHashCode(coordNow)
                            + " newExo=@" + System.identityHashCode(newExo)
                            + (coordNow == newExo ? " ✓ match" : " ✗ MISMATCH"));
                } catch (Exception e) {
                    logWarn("9.x PlayNext: coord identity check failed: " + e.getMessage());
                }
            }

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

            // Re-invoke natively so the next track actually loads onto the new player.
            // internalPlayNext=true causes the hook to pass through immediately.
            // We then re-enforce volume=0 synchronously, before any poll tick.
            Object atad = lastAtadRef.get();
            if (atad instanceof MedialibPlayerAccess) {
                internalPlayNext = true;
                try {
                    ((MedialibPlayerAccess) atad).patch_playNextInQueue();
                } catch (Exception e) {
                    internalPlayNext = false;
                    logWarn("PlayNext: re-invoke threw: " + e.getMessage());
                }
                try {
                    newExo.patch_setVolume(0.0f);
                    logInfo("PlayNext: volume re-enforced to 0 after native");
                } catch (Exception ignored) {}
            } else {
                logWarn("PlayNext: atad ref lost — cannot re-invoke native");
            }

            logInfo("PlayNext: old player preserved, polling for new track ready");
            pollForNewTrackReady(newExo);
            return true; // block original call

        } catch (Exception e) {
            logError("onBeforePlayNext error", e);
            cleanupAllPlayers();
            if (audioModeWasForced) {
                audioModeWasForced = false;
                restoreVideoModeSilently();
            }
            return false;
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
                + " deferred=" + deferredSwapPending
                + " atad=" + (atadInstance != null) + "]");

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

                // Keep new player silent while waiting for READY. The native
                // playNextInQueue (auto-advance) runs after our void hook and
                // resets the player to volume 1.0 — re-enforce on every tick.
                try { newPlayer.patch_setVolume(0.0f); } catch (Exception ignored) {}

                try {
                    int state = newPlayer.patch_getPlaybackState();
                    if (state == STATE_READY) {
                        // Diagnostic: verify coordinator is still pointing to this player
                        // and that Lcrh.N has a listener registered (9.x only).
                        if (is9x && activeCoordinator != null) {
                            try {
                                Object coordNow = activeCoordinator.patch_getExoPlayer();
                                int lnCount = newPlayer.patch_getDirectListenerCount();
                                logInfo("READY: newPlayer=@" + System.identityHashCode(newPlayer)
                                        + " coordinator.exoPlayer=@" + System.identityHashCode(coordNow)
                                        + (coordNow == newPlayer ? " ✓ coord match" : " ✗ COORD MISMATCH")
                                        + " Lcrh.N.size=" + lnCount
                                        + (lnCount > 0 ? " ✓" : " ✗ NO DIRECT LISTENERS — MediaSession will stay PAUSED"));
                            } catch (Exception e) {
                                logWarn("READY: diagnostic check failed: " + e.getMessage());
                            }
                        }
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
            if (is9x) {
                // On 9.x the coordinator's UI listener (auge.b:Lcou, in Lcrh.N) was already
                // moved from the outgoing player to the incoming player at crossfade start time
                // (in onBeforeStopVideo). No listener migration needed here.
                // The old player's N set is now empty; it is safe to release after fade-out.
                logInfo("onPendingPlayerReady (9.x): coordinator listener already migrated at start");
            }

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
                    logInfo("onPendingPlayerReady: outgoing remaining=" + actualRemaining
                            + "ms fadeDuration=" + fadeDuration + "ms");
                    if (actualRemaining < fadeDuration) {
                        fadeOutDuration = Math.max(150, actualRemaining);
                        logInfo("Fade-out shortened to " + fadeOutDuration
                                + "ms to match remaining audio (was " + fadeDuration + "ms)");
                    }
                }
            } catch (Exception e) {
                logDebug("Could not read outgoing remaining time: " + e.getMessage());
            }
            fadingOutPlayers.add(new FadingPlayer(outgoing, fadeOutDuration, curve));
            pendingOutPlayer = null;
            logInfo("Original outgoing player @" + System.identityHashCode(outgoing)
                    + " → fade-out list (" + fadeOutDuration + "ms)");
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
                                + "ms trigger@" + (fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS) + "ms");
                    }

                    if (dur <= fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS) {
                        mainHandler.postDelayed(this, MONITOR_POLL_MS);
                        return;
                    }

                    if (remaining <= fadeDuration + AUTO_ADVANCE_TRIGGER_BUFFER_MS && remaining > 0) {
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

        if (deferredSwapRunnable != null) {
            mainHandler.removeCallbacks(deferredSwapRunnable);
            deferredSwapRunnable = null;
        }
        crossfadeInPlayer = null;
        pendingInPlayer = null;
        pendingOutPlayer = null;
        activeCoordinator = null;
        crossfadeInProgress = false;
        deferredSwapPending = false;
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
            logInfo("fade-in pre-start: @" + System.identityHashCode(inPlayer)
                    + " volume enforced to 0 before setPlayWhenReady");
        } catch (Exception e) {
            logWarn("fade-in pre-start: failed to zero volume: " + e.getMessage());
        }

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

    /**
     * InvocationHandler that forwards every method call to a captured real listener.
     * Used by {@link #migrateListeners} to create proxy wrappers around migrated bxi objects.
     *
     * <p>Storing the target in a named field (rather than a lambda capture) lets
     * {@link #unwrapForwardingTarget} recover the original listener and avoid
     * proxy-of-proxy accumulation on consecutive skips.</p>
     */
    private static final class ForwardingHandler implements java.lang.reflect.InvocationHandler {
        final Object target;

        ForwardingHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw (cause != null) ? cause : e;
            }
        }
    }

    /**
     * If {@code listener} is a {@link java.lang.reflect.Proxy} backed by a
     * {@link ForwardingHandler}, returns the handler's {@code target} (unwrapping one
     * layer). Recurses until the deepest non-proxy target is reached.
     * Returns {@code listener} unchanged if it is not a forwarding proxy.
     */
    private static Object unwrapForwardingTarget(Object listener) {
        while (java.lang.reflect.Proxy.isProxyClass(listener.getClass())) {
            java.lang.reflect.InvocationHandler h =
                    java.lang.reflect.Proxy.getInvocationHandler(listener);
            if (h instanceof ForwardingHandler) {
                listener = ((ForwardingHandler) h).target;
            } else {
                break; // Foreign proxy — leave it alone.
            }
        }
        return listener;
    }

    /**
     * Creates a {@link java.lang.reflect.Proxy} that implements every interface
     * found in {@code realListener}'s class hierarchy and forwards all calls to it.
     *
     * <p>The proxy has a different object identity from {@code realListener}, so
     * {@link java.util.concurrent.CopyOnWriteArraySet#add} always succeeds even when
     * {@code realListener} is already in the set.  ExoPlayer's listener dispatch
     * uses {@code invoke-interface} against the stored Object, so a Proxy that
     * implements the same interfaces receives the call correctly.</p>
     *
     * @return the proxy, or {@code null} if no interfaces are discoverable (should never happen).
     */
    private static Object createForwardingProxy(Object realListener) {
        java.util.Set<Class<?>> ifaceSet = new java.util.LinkedHashSet<>();
        for (Class<?> cls = realListener.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Class<?> iface : cls.getInterfaces()) {
                ifaceSet.add(iface);
            }
        }
        if (ifaceSet.isEmpty()) return null;
        Class<?>[] ifaces = ifaceSet.toArray(new Class<?>[0]);
        try {
            return java.lang.reflect.Proxy.newProxyInstance(
                    realListener.getClass().getClassLoader(),
                    ifaces,
                    new ForwardingHandler(realListener));
        } catch (Exception e) {
            logWarn("createForwardingProxy failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Transfers the coordinator's UI listener (and any other external Player.Listener
     * registrations) from the outgoing player to the incoming player, then clears the
     * outgoing player's listener set so it no longer emits events.
     *
     * <p>Called on 9.x only, at STATE_READY time (after the native
     * stopVideo→loadVideo→playVideo chain has fully completed on the new player).
     *
     * <h3>Why this is needed on 9.x</h3>
     * {@code patch_setPlayerWithBindings} writes the new player into the coordinator's
     * {@code exoPlayer} field.  When the coordinator's internal player-transition method
     * is not found at patch time, the bridge falls back to a raw {@code iput-object} that
     * performs NO listener migration.  The coordinator's MedialibPlayerEvents listener
     * (which drives the seekbar and play/pause state) therefore remains on the OLD player's
     * {@link java.util.concurrent.CopyOnWriteArraySet}, causing UI disconnection.
     *
     * <h3>Strategy (B2 proxy approach)</h3>
     * <ol>
     *   <li>Snapshot the old player's listener set (cats = ListenerHolder wrappers).</li>
     *   <li>Clear the old player's set — silences it immediately.</li>
     *   <li>Collect the real bxi from each cat, unwrapping any prior proxy layers
     *       (avoids proxy-of-proxy accumulation on consecutive skips).</li>
     *   <li>Build the set of bxi already registered on the new player (factory listeners)
     *       so we can skip duplicates.</li>
     *   <li>For each bxi NOT already in the new player: wrap it in a
     *       {@link ForwardingHandler} {@link java.lang.reflect.Proxy} and register via
     *       {@code cau.add}.  The proxy has a fresh object identity, bypassing
     *       {@link java.util.concurrent.CopyOnWriteArraySet}'s equality check, while
     *       ExoPlayer's {@code invoke-interface} dispatch still reaches the real listener.</li>
     *   <li>Fallback: if proxy creation or {@code cau.add} fails for every listener,
     *       copy the original cat objects directly (v151 behaviour).</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private static void migrateListeners(ExoPlayerAccess fromPlayer, ExoPlayerAccess toPlayer) {
        try {
            Object fromSetObj = fromPlayer.patch_getListenerSet();
            if (!(fromSetObj instanceof java.util.concurrent.CopyOnWriteArraySet)) {
                logWarn("migrateListeners: unexpected set type — clearing only");
                detachPlayerListeners(fromPlayer);
                return;
            }
            java.util.concurrent.CopyOnWriteArraySet<Object> from =
                    (java.util.concurrent.CopyOnWriteArraySet<Object>) fromSetObj;

            // Snapshot before clearing so we have the original cat objects for the fallback.
            java.util.List<Object> catSnapshot = new java.util.ArrayList<>(from);

            // Extract real listeners (unwrap any proxy layers from prior skips).
            java.util.List<Object> realListeners = new java.util.ArrayList<>(catSnapshot.size());
            for (Object cat : catSnapshot) {
                if (cat instanceof ListenerWrapperAccess) {
                    Object raw = ((ListenerWrapperAccess) cat).patch_getWrappedListener();
                    if (raw != null) realListeners.add(unwrapForwardingTarget(raw));
                }
            }

            // Silence old player immediately.
            from.clear();

            // Inspect new player's existing listener set.
            Object toSetObj = toPlayer.patch_getListenerSet();
            java.util.concurrent.CopyOnWriteArraySet<Object> toSet =
                    (toSetObj instanceof java.util.concurrent.CopyOnWriteArraySet)
                    ? (java.util.concurrent.CopyOnWriteArraySet<Object>) toSetObj : null;
            int toSizeBefore = toSet != null ? toSet.size() : -1;

            // Build the set of real listeners already in the new player so we can skip
            // factory duplicates (they are already registered at construction time).
            java.util.Set<Object> alreadyPresent = new java.util.HashSet<>();
            if (toSet != null) {
                for (Object cat : new java.util.ArrayList<>(toSet)) {
                    if (cat instanceof ListenerWrapperAccess) {
                        Object raw = ((ListenerWrapperAccess) cat).patch_getWrappedListener();
                        if (raw != null) alreadyPresent.add(unwrapForwardingTarget(raw));
                    }
                }
            }

            // Identify and proxy only the coordinator's UI listener.
            //
            // Two filter passes:
            //  1. Identity check against new player's factory cats (shared-static bxi):
            //     filters factory listeners whose bxi is the same instance across all players.
            //  2. Coordinator-identity check (coordinatorListenerBxi):
            //     filters per-player factory listeners whose bxi is a fresh instance per
            //     ExoPlayer and therefore NOT caught by pass 1.  Once we identify the
            //     coordinator bxi on the first crossfade we record it and only migrate
            //     that exact object on all subsequent crossfades.
            int registered = 0;
            int skipped = 0;
            for (Object real : realListeners) {
                if (alreadyPresent.contains(real)) {
                    // Shared-static factory listener — already in new player, skip.
                    skipped++;
                    continue;
                }
                if (coordinatorListenerBxi != null && real != coordinatorListenerBxi) {
                    // Per-player factory listener (different instance per ExoPlayer).
                    // Migrating it would leak old-player state and cause accumulation.
                    skipped++;
                    continue;
                }
                // Either coordinatorListenerBxi is null (first crossfade) or real IS it.
                Object proxy = createForwardingProxy(real);
                if (proxy == null) {
                    logWarn("migrateListeners: proxy creation returned null for "
                            + real.getClass().getName());
                    continue;
                }
                try {
                    toPlayer.patch_addListener(proxy);
                    registered++;
                    if (coordinatorListenerBxi == null) {
                        // Record the coordinator bxi on first successful migration.
                        coordinatorListenerBxi = real;
                        logInfo("migrateListeners: identified coordinator bxi: "
                                + real.getClass().getName()
                                + "@" + System.identityHashCode(real));
                    }
                } catch (Exception e) {
                    logWarn("migrateListeners: cau.add threw: " + e.getMessage());
                }
            }

            if (registered > 0 || skipped > 0) {
                logInfo("migrateListeners: registered=" + registered
                        + " skipped=" + skipped
                        + " total=" + realListeners.size()
                        + " toPlayer had=" + toSizeBefore
                        + " @" + System.identityHashCode(fromPlayer)
                        + " → @" + System.identityHashCode(toPlayer));
            } else {
                // All proxy creations or cau.add calls failed — fall back to v151 behaviour:
                // copy the original cat objects directly into the new player's set.
                logWarn("migrateListeners: proxy path failed — copying " + catSnapshot.size()
                        + " original cats to toPlayer (had " + toSizeBefore + ")");
                if (toSet != null) toSet.addAll(catSnapshot);
            }
        } catch (Exception e) {
            logWarn("migrateListeners failed", e);
            detachPlayerListeners(fromPlayer);
        }
    }

    /**
     * Clears the external Player.Listener set on a player being retired from active duty.
     * Used as a fallback or for players that were never promoted (chained skips).
     */
    private static void detachPlayerListeners(ExoPlayerAccess player) {
        try {
            Object listenerSet = player.patch_getListenerSet();
            if (listenerSet instanceof java.util.concurrent.CopyOnWriteArraySet) {
                ((java.util.concurrent.CopyOnWriteArraySet<?>) listenerSet).clear();
                logInfo("Detached @" + System.identityHashCode(player)
                        + " from UI listeners (cleared listener set)");
            }
        } catch (Exception e) {
            logWarn("Could not detach player from UI listeners", e);
        }
    }

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
        deferredSwapPending = false;
        currentFadeInVolume = 0.0f;
        coordinatorListenerBxi = null;
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
                try { playerState = fp.player.patch_getPlaybackState(); } catch (Exception ignored) {}
                try {
                    fp.player.patch_setVolume(Math.max(0.0f, vol));
                    long elapsed = System.currentTimeMillis() - fp.startTimeMs;
                    if (elapsed % 500 < TICK_MS) {
                        logDebug(String.format(
                                "fade-out: @%d vol=%.2f state=%d elapsed=%dms",
                                System.identityHashCode(fp.player), vol, playerState, elapsed));
                    }
                } catch (Exception e) {
                    logWarn("fade-out setVolume threw: " + e.getMessage()
                            + " player=@" + System.identityHashCode(fp.player)
                            + " state=" + playerState);
                }

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

    // ------------------------------------------------------------------ //
    //  Activity lifecycle                                                 //
    // ------------------------------------------------------------------ //

    public static void onActivityStop() {
        activityRunning = false;
        // Do not stop the auto-advance monitor here — crossfade should continue
        // even when the screen locks or the app is minimised (#1311).
        if (crossfadeInProgress) {
            logInfo("onActivityStop: aborting crossfade");
            abortCrossfadeNow();
        }
    }

    public static void onActivityStart() {
        activityRunning = true;
        if (isEnabled() && !sessionPaused) {
            startAutoAdvanceMonitor();
        }
    }

    public static boolean isSessionPaused() {
        return sessionPaused;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
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

            Utils.showToastShort(str(sessionPaused
                    ? "morphe_music_crossfade_paused_toast"
                    : "morphe_music_crossfade_resumed_toast"));
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
                Utils.showToastShort(str("morphe_music_crossfade_video_mode_disabled_toast"));
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

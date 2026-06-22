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
import android.media.AudioPlaybackConfiguration;
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
        MILLISECONDS_10000(10_000),
        MILLISECONDS_11000(11_000),
        MILLISECONDS_12000(12_000);

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
        /**
         * Calls the coordinator's own playNextInQueue method (auih.y()V) directly.
         * The auto-advance monitor and onBeforePlayNext re-invoke must use this
         * instead of atad.patch_playNextInQueue() (which calls atzq.p() → Lausd→y()V).
         * auih does NOT implement Lausd, so the Lausd dispatch path never reaches
         * the hooked auih.y()V — meaning onBeforePlayNext would never fire.
         */
        void patch_playNextInQueueDirect();
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
        /** Calls atad.stopVideo(REASON_DIRECTOR_RESET=5) through the hooked method. Used by 8.x and 9.x auto-advance monitor. */
        void patch_forceStopVideo();
        /** Calls atad.stopVideo(REASON_STOP=1).  Currently unused — kept for future flows. */
        void patch_forceLoadVideo();
        /** Re-issues loadVideo (atzq.o) with a cached aues descriptor — REPEAT_SINGLE crossfade-onto-self. */
        void patch_loadVideoWith(Object descriptor);
    }

    /** Audio / video toggle (nba). */
    public interface VideoToggleAccess {
        boolean patch_isAudioMode();
        void patch_forceAudioMode();
        void patch_triggerToggle();
        void patch_forceAudioModeSilent();
        void patch_restoreVideoModeSilent();
        /**
         * Broadcast variant: calls nlw.setState → chxp.mo6606iF, notifying all
         * subscribers (including nmi).  Use to resync subscribers whose cached state
         * is out of sync with chxp after a silent toggle.
         */
        void patch_restoreVideoMode();
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

    private static volatile boolean isCrossfadePaused = false;
    private static volatile boolean inVideoMode = false;
    private static volatile long manualToggleSuppressionUntil = 0;
    private static volatile boolean crossfadeInProgress = false;
    private static volatile boolean audioModeWasForced = false;
    private static volatile boolean activityRunning = false;
    /**
     * Tracks whether the outer MedialibPlayer is currently in a playing state.
     * Set to true by onPlayVideo, false by onPauseVideo. Prevents the 9.x crossfade
     * from resuming a paused outgoing player when the user selects a new song while paused.
     */
    private static volatile boolean playerIsPlaying = true;
    /**
     * True when a crossfade was initiated by the auto-advance monitor (via onBeforePlayNext).
     * Used to distinguish the natural track-end stopVideo(5) (which fires ~fadeDuration ms
     * after the crossfade started) from a genuine user double-skip, avoiding a false
     * handleChainedSkip call that would corrupt the auto-advance crossfade state.
     * Cleared whenever crossfadeInProgress is cleared.
     */
    private static volatile boolean autoAdvanceCrossfadeActive = false;
    /** Retained for cleanup symmetry only. */
    private static volatile boolean monitorCrossfadeActive = false;
    /**
     * 9.x auto-advance: true after onBeforeStopVideo has pre-started the outgoing
     * player's fade-out at coordinator swap time.  Tells onPendingPlayerReady not
     * to re-add the outgoing to the fade list (it's already in flight).  Decouples
     * fade-out integrity from new-player load latency, which can exceed the
     * remaining audio on the outgoing track and otherwise causes the fade-out to
     * be shortened or skipped entirely.
     */
    private static volatile boolean outgoingFadePreStarted = false;

    /**
     * #1549 cast-investigation placeholder. Currently unused — kept here for the
     * future Option C fix (skip crossfade while an MDX session is active).  The
     * flag is not yet wired up to MDX events; the v229 investigation captures
     * cast disconnects via adb logcat correlation rather than direct hooks.
     */
    private static final boolean isCasting = false;

    /**
     * Set at patch time via sput-boolean — true when running on YTM 9.x.
     * On 9.x, blocking stopVideo also blocks playVideo (same call chain),
     * so we use a deferred coordinator swap instead of blocking native.
     */
    public static final boolean is9x = VersionCheckPatch.IS_9_00_OR_GREATER;
    
    /**
     * 9.x: When true, the injected early-return in cwh.U()V prevents the Lcvu Runnable from
     * being posted to the handler. This blocks cvu.run() → cwh.b.d() → CopyOnWriteArraySet.clear()
     * which would otherwise destroy auih.k (MediaSession listener) in the shared cwh.b Lcgd.
     * Set to true synchronously before patch_release() on an outgoing crossfade player, cleared
     * in the finally block. Only needed on 9.x (crh.P() calls cwh.U() on the shared singleton).
     */
    public static volatile boolean suppressCwhU = false;

    /**
     * Read once at class init.  Pairs with {@code rebootApp=true} on
     * {@link Settings#CROSSFADE_ENABLED}: toggling the setting requires
     * an app restart, so the value is frozen for the process lifetime.
     * When false, the JIT can dead-code-eliminate every hook body.
     */
    private static final boolean CROSSFADE_ENABLED = Settings.CROSSFADE_ENABLED.get();

    /**
     * True when we have set up crossfade state but deliberately NOT swapped
     * the coordinator yet (9.x path). The swap is deferred until onPlayVideo
     * fires (or the postDelayed fallback runs after the native cycle completes).
     */
    private static volatile boolean deferredSwapPending = false;

    /**
     * Fallback Runnable for the 9.x deferred swap.
     * Scheduled at DEFERRED_SWAP_DELAY_MS after allowing native stopVideo to proceed.
     * Canceled if onPlayVideo fires first, or if the crossfade is aborted.
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
    /** Delay between setting fade-out volume to 0 and releasing the player.
     *  Lets ExoPlayer's AudioTrack drain its buffered frames (typically ~250ms deep)
     *  so the abrupt teardown doesn't cut off any still-queued audio. */
    private static final long RELEASE_DRAIN_DELAY_MS = 150;
    private static final int READY_POLL_MS = 100;
    private static final int READY_TIMEOUT_MS = 10000;
    private static final int STATE_IDLE = 1;
    private static final int STATE_ENDED = 4;
    private static final int STATE_READY = 3;
    private static final int REASON_DIRECTOR_RESET = 5;

    /**
     * #1671: if the pending factory player sits at {@link #STATE_IDLE} continuously
     * for this long AND no loadVideo has been issued for the transition
     * ({@link #pendingLoadIssued}), no content is coming — the queue was dismissed/
     * ended out from under us — so recover instead of waiting out
     * {@link #READY_TIMEOUT_MS}.  Once a load HAS been issued this timer is moot
     * (the IDLE branch is gated on {@code !pendingLoadIssued}); it only bounds the
     * no-load-at-all case, which is why it can be generous.
     *
     * <p>Was 600 ms, which tore down legitimate locked auto-advances: the queue-advance
     * that issues the load is deferred under background throttling (Xiaomi/MIUI), so the
     * pre-load IDLE dwell can exceed 600 ms even though content is on its way.  2000 ms
     * clears any realistic queue-advance latency while still recovering a genuinely
     * dismissed/ended queue far faster than the 10 s full timeout.
     */
    private static final long IDLE_LOAD_FAIL_MS = 2000;

    /**
     * #1671: how long after a dismiss-trigger ({@link #onQueueDismissed}) we treat
     * an incoming stopVideo as part of the dismiss (and pass it through WITHOUT
     * starting a crossfade).  YTM tears down playback on dismiss via the same
     * stopVideo(5) a manual skip uses, so the dismiss UI triggers set this window
     * to tell us "the next stop is a dismiss, not a skip."  Kept short so a real
     * skip moments after a dismiss isn't wrongly suppressed.  On 9.x the stock
     * "Dismiss queue" path fires its stopVideo(5) ASYNChronously after clearQueue,
     * so the window must comfortably cover that dispatch; 1.5 s is safe because a
     * dismiss empties the queue — a real skip can't occur until the user starts a
     * whole new queue, which takes far longer than this window.
     */
    private static final long DISMISS_WINDOW_MS = 1500;
    private static volatile long dismissWindowUntilMs = 0;
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

    private static final List<FadingPlayer> fadingOutPlayers =
            Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean fadingLoopRunning = false;

    private static WeakReference<Object> lastAtadRef = new WeakReference<>(null);
    private static WeakReference<Object> lastNbaRef = new WeakReference<>(null);
    private static final boolean internalToggle = false;
    private static volatile boolean internalPlayNext = false;
    /** Marks the auih.y() hook to pass through when the monitor invoked it. */
    private static volatile boolean monitorTriggeredSkip = false;
    /**
     * True when the monitor advanced the queue (MEDIA_NEXT dispatch).  Causes the
     * OUTGOING's natural-end stopVideo(5) to be BLOCKED instead of allowed — without
     * this, YTM's gapless code would advance the queue a second time (double-skip).
     */
    private static volatile boolean queueAdvancedByMonitor = false;
    private static Runnable autoAdvanceMonitorRunnable = null;

    private static int playersCreated = 0;
    private static int playersReleased = 0;

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
    private static int lastAtadIdentity = 0;

    public static boolean onBeforeStopVideo(Object atadInstance, int reason) {
        if (!CROSSFADE_ENABLED) return false;

        // #1671: the user just dismissed the queue / swipe-dismissed the miniplayer
        // (signalled by onQueueDismissed from the dismiss UI triggers).  YTM tears
        // down playback via this same stopVideo(5), which is otherwise
        // indistinguishable from a manual skip — engaging crossfade here spins up a
        // phantom factory player that never loads (queue is empty) and leaves the
        // outgoing playing in the background.  Pass the stop through untouched so
        // playback ends and the queue clears cleanly.  The window is left to expire
        // by time so the 1-2 internal stops of the dismiss sequence are all covered.
        if (SystemClock.uptimeMillis() < dismissWindowUntilMs) {
            logInfo(() -> "stopVideo(" + reason + "): within dismiss window — passing through, no crossfade");
            return false;
        }

        // #1549: skip crossfade when audio is routed to a cast/mirror receiver.
        // Lets YTM's native gapless transition handle the song change so the
        // cast layer doesn't see our coordinator-swap MediaSession flicker.
        // Only gate when no crossfade is already in flight — if one is in
        // progress (cast started mid-fade), let it complete normally.
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
                        // Block: queue already advanced via MEDIA_NEXT; letting native run
                        // would double-advance to song N+2.
                        logDebug(() -> "stopVideo(5): auto-advance + queue already advanced — BLOCKING natural-end");
                        return true;
                    }
                    return false;
                }
                return handleChainedSkip(atadInstance);
            }
            if (is9x) {
                // 9.x: native stopVideo(5) body calls stopVideo(1)/loadVideo/playVideo to
                // load the next track on the new coordinator player; must pass through.
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

            // Primary signal: the monitor explicitly set queueAdvancedByMonitor=true
            // before dispatching MEDIA_NEXT.  This is definitive — it doesn't depend
            // on the position-at-stopVideo-time relative to AUTO_ADVANCE_THRESHOLD_MS,
            // which fails for any fade duration ≥ 5 s. (The monitor triggers at
            // remaining = fadeDuration + buffer, so for an 8 s fade the stopVideo
            // arrives with ~8 s remaining and the old remaining-only check
            // misclassified it as a manual skip — leaving cwh attached on the
            // outgoing and causing a double-advance when the outgoing naturally
            // ends in the fade pool.)
            boolean isAutoAdvance = queueAdvancedByMonitor;
            try {
                long pos = currentExo.patch_getCurrentPosition();
                long duration = currentExo.patch_getDuration();
                long remaining = (duration > 0) ? duration - pos : Long.MAX_VALUE;
                // Fallback heuristic: covers paths where the monitor flag wasn't set
                // (e.g. YTM's own natural-end fired before our monitor could).
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

            // 9.x volume-fade auto-advance: the monitor started fading out the outgoing
            // player's volume. Allow the natural transition through — bacw.I() will create
            // the incoming player and onBeforeLoadVideo will start the fade-in.
            if (is9x && isAutoAdvance && autoAdvanceCrossfadeActive) {
                logDebug(() -> "9.x: volume-fade auto-advance — allowing stopVideo(5) (outgoing fade running)");
                return false;
            }
            // If a manual skip fires while a 9.x volume-fade is running, abort the fade
            // and fall through to the normal crossfade path. The pre-started outgoing
            // fade-out (if any) keeps running independently on the original outgoing
            // player; the new manual skip creates a different outgoing.
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
                    // Set when the monitor triggers via patch_playNextInQueueDirect → auih.y()V.
                    // Prevents the natural track-end auih.y() from calling handleChainedSkip
                    // via onBeforePlayNext, which would corrupt the already-in-flight crossfade.
                    autoAdvanceCrossfadeActive = true;
                    logDebug(() -> "9.x: auto-advance crossfade → autoAdvanceCrossfadeActive=true");

                    // Detach cwh at swap time (auto-advance only): outgoing reaches natural-
                    // end during the fade and would fire onEnded through the shared
                    // MedialibPlayerEvents bus, which YTM mis-routes to the INCOMING and
                    // double-advances the queue.  Manual-skip keeps cwh attached so the
                    // far-from-end outgoing keeps reporting pause/seek/position events.
                    try {
                        currentExo.patch_detachCwhFromEventDispatch();
                        logDebug(() -> "9.x auto-advance: detached cwh from OUTGOING @"
                                + System.identityHashCode(currentExo) + " at swap time");
                    } catch (Exception e) {
                        logWarn(()-> "9.x auto-advance: cwh detach on outgoing failed: " + e.getMessage());
                    }
                }
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
                        logDebug(() -> "9.x: pre-removed coord listener from outgoing @"
                                + System.identityHashCode(currentExo));
                    }
                } catch (Exception e) {
                    logWarn(()-> "9.x: pre-remove coord listener failed: " + e.getMessage());
                }

                coordinator.patch_setPlayerWithBindings(newExo);
                logDebug(() -> "9.x: swapped coordinator → new player @" + System.identityHashCode(newExo)
                        + " via patch_setPlayerWithBindings (Lcou backref updated)");

                // Re-register Lcou into the new player's Lcrh.N.
                // patch_setPlayerWithBindings (the coordinator's transition method) only migrates
                // cau-level listeners — it never touches Lcrh.N. Without this, Lcou is in neither
                // player's Lcrh.N and MediaSession never receives onIsPlayingChanged(true).
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

                // Re-enable the outgoing player for audible fade-out.
                // Use a timestamp check: if pauseVideo fired within PAUSE_TO_STOP_INTERNAL_WINDOW_MS
                // of this stopVideo, it was YTM-internal (skip setup), not a genuine user pause.
                // A genuine user pause precedes the next song selection by seconds, so the gap
                // will be >> 500ms and playerIsPlaying=false will correctly keep the player silent.
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

                // 9.x auto-advance: start the outgoing fade-out NOW (at swap time)
                // rather than waiting for the new player to reach READY. On 9.x the
                // MEDIA_NEXT skip path triggers a cold load, not a pre-warmed gapless
                // buffer, so new-player READY latency can exceed the remaining audio
                // on the outgoing track. If we waited for READY, onPendingPlayerReady
                // would shorten the fade-out to actualRemaining (min 150ms) or skip
                // it entirely via the trackAlreadyEnded path. Pre-starting decouples
                // fade-out integrity from load latency: the outgoing always gets the
                // full configured fade duration and reaches silence gracefully.
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
                return false; // Allow native stopVideo chain → loads track onto newExo
            } else {
                // 8.x path: block native, swap coordinator immediately so loadVideo
                // routes content onto the new player.
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

    /**
     * Handles a skip-next that arrives while a crossfade is already in progress.
     * Demotes the current incoming player to a quick fade-out, creates a new
     * player, and swaps it onto the coordinator so the native loadVideo flow
     * naturally loads the next track onto it.
     */
    private static boolean handleChainedSkip(Object atadInstance) {
        // When the activity is stopped/destroyed (recents-view, swipe-clear), YTM
        // emits rapid stopVideo(5) bursts as part of its own teardown.  Treating
        // those as user chained skips creates factory players that can never
        // reach READY (the activity is going away), leading to a 10 s timeout
        // and emergency cleanup.  Decline chained-skip engagement during these
        // windows and let native through — onActivityDestroy will clean up the
        // existing in-flight crossfade.
        if (!activityRunning) {
            logInfo(() -> "CHAINED SKIP suppressed — activity not running (likely teardown)");
            return false;
        }
        logDebug(() -> "stopVideo(5): CHAINED SKIP — creating new player, deferring demotion until READY");

        if (is9x) {
            long elapsed = System.currentTimeMillis() - deferredSwapStartTime;
            if (elapsed < INTERNAL_CALL_WINDOW_MS) {
                // This is the 9.x-internal second stopVideo(5) that always fires ~1ms
                // after the first as part of the native track-transition sequence.
                // It is NOT a user double-skip — pass it through untouched.
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

            // Save and clear pendingInPlayer before factory call.
            ExoPlayerAccess oldPending = pendingInPlayer;
            pendingInPlayer = null;

            ExoPlayerAccess newExo = createNewPlayer(coordinator);
            if (newExo == null) {
                logError(() -> "Chained skip: factory failed — aborting crossfade");
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

            // Re-register Lcou into new player's Lcrh.N (9.x only).
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

            return !is9x; // 8.x: block native stopVideo; 9.x: allow native chain to load track onto newExo
        } catch (Exception e) {
            logError(()-> "handleChainedSkip error", e);
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
                // On 9.x the timeline field is final; the factory cannot re-set it.
                // Restore the old value so the shared state remains coherent.
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
     * Returns true to block the native playNextInQueue.  Sets up crossfade state and
     * re-invokes via internalPlayNext=true so native loads the next track on our new
     * player, then re-enforces volume=0 to prevent a blip.
     */
    public static boolean onBeforePlayNext(Object coordinatorInstance) {
        if (!CROSSFADE_ENABLED) return false;

        // #1549: skip crossfade when audio is routed to a cast/mirror receiver.
        // Native gapless transition runs unchanged.
        if (!crossfadeInProgress && isAudioRoutedToCast()) {
            logDebug(() -> "playNext: skip — audio routed to cast/mirror (#1549)");
            return false;
        }

        // Monitor-triggered native skip: let auih.y()V run so it calls stopVideo(5),
        // which onBeforeStopVideo will intercept for a true overlap crossfade.
        if (monitorTriggeredSkip) {
            monitorTriggeredSkip = false;
            logDebug(() -> "PlayNext: monitor-triggered — allowing native auih.y()V (stopVideo intercepted by onBeforeStopVideo)");
            return false;
        }

        // Internal re-invoke: let native through immediately.
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
                // YTM's native gapless mechanism fired playNextInQueue after our auto-advance
                // monitor already triggered it. Block this duplicate call to prevent loading
                // the next-next track onto the incoming player mid-crossfade.
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
                // Pre-remove coord listener from outgoing player before transition.
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

            // Re-register Lcou into new player's Lcrh.N (9.x only).
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

            // Re-invoke via atzq.p()→Lausd→y()V to advance the queue and trigger the
            // native loadVideo onto the new coordinator player.  internalPlayNext guards
            // against re-entry if our hook ever fires during the re-invoke.
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
            return true; // block original call

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

    // --- REPEAT_SINGLE crossfade-onto-self state ---
    /** Lnwu loop enum: LOOP_OFF=0, LOOP_ALL=1, LOOP_ONE=2, LOOP_DISABLED=3. */
    private static final int LOOP_ONE_ORDINAL = 2;
    /** Live repeat-single state, tracked via the MediaSession loop adapter hook. */
    private static volatile boolean repeatSingleActive = false;
    /**
     * Most recent loadVideo descriptor (aues) — the current track's load intent.
     * Re-issued via patch_loadVideo to crossfade the song onto itself on REPEAT_SINGLE.
     */
    private static volatile Object lastLoadDescriptor = null;

    /**
     * Hooked at MediaSessionLoopStateAdapter (kyb.a) entry with the Lnwu loop-state
     * enum.  Tracks whether REPEAT_SINGLE (LOOP_ONE) is active so the auto-advance
     * monitor can crossfade the song onto itself instead of advancing the queue.
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
     * 9.x atzq.o (loadVideo) entry hook.  Caches the current track's descriptor
     * (aues) for REPEAT_SINGLE re-issue; fade-in itself is driven by
     * onPendingPlayerReady.  Caches unconditionally so {@link #lastLoadDescriptor}
     * always holds the most-recently-loaded (i.e. current) track's load intent.
     */
    public static void onBeforeLoadVideo(Object newAtzqInstance, Object descriptor) {
        if (!is9x) return;
        if (descriptor != null) {
            lastLoadDescriptor = descriptor;
        }
        // Mark that content is on its way for an in-flight crossfade transition, so the
        // STATE_IDLE fast-recovery in pollForNewTrackReady won't abandon a slow load.
        if (crossfadeInProgress) {
            pendingLoadIssued = true;
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
    /**
     * Wall-clock time of the last onPauseVideo call.
     * Used to distinguish YTM-internal pauseVideo calls (which arrive ~1–50ms before
     * a skip-triggered stopVideo) from genuine user pauses (seconds earlier).
     * If pauseVideo and stopVideo(5) arrive within PAUSE_TO_STOP_INTERNAL_WINDOW_MS,
     * the pause was internal and we still re-enable the outgoing player for fade-out.
     */
    private static volatile long lastPauseVideoMs = System.currentTimeMillis();
    /**
     * If pauseVideo fired within this many ms before a crossfade-triggering stopVideo(5),
     * the pause is treated as YTM-internal (skip setup) not a genuine user pause.
     * YTM typically calls pauseVideo → stopVideo within ~10–50ms during a skip;
     * a genuine user pause precedes the next song selection by seconds.
     */
    private static final long PAUSE_TO_STOP_INTERNAL_WINDOW_MS = 500;

    /**
     * Hooked at the top of MedialibPlayer.pauseVideo.
     * Returns true to BLOCK the pause, false to allow.
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
     * Hooked at the top of MedialibPlayer.playVideo.
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

        // Coerce video → audio at song-start (shouldBlockVideoToggle catches manual
        // toggles but not auto-loaded video-mode songs like music videos).
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
    private static long pollIdleStreakStartMs = 0;
    /**
     * #1671 follow-up (screen-lock reliability): true once YTM has issued a loadVideo
     * for the current pending crossfade transition.  The STATE_IDLE fast-recovery below
     * must only fire when NO load was issued (a genuine dismiss / end-of-queue — content
     * will never arrive).  A slow-but-legitimate load also dwells at STATE_IDLE before
     * STATE_BUFFERING, and that dwell stretches under aggressive background throttling
     * (e.g. Xiaomi/MIUI while the screen is locked) — without this guard the 600 ms timer
     * misfires and tears down a perfectly good locked auto-advance.  Reset per transition.
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

                // Keep new player silent while waiting for READY. The native
                // playNextInQueue (auto-advance) runs after our void hook and
                // resets the player to volume 1.0 — re-enforce on every tick.
                try { newPlayer.patch_setVolume(0.0f); } catch (Exception ignored) {}

                try {
                    int state = newPlayer.patch_getPlaybackState();
                    if (state == STATE_READY) {
                        logDebug(() -> "Pending track READY — promoting to crossfade");
                        onPendingPlayerReady(newPlayer);
                        return;
                    }

                    if (state == STATE_ENDED) {
                        logError(() -> "Pending player ENDED unexpectedly — aborting");
                        recoverFromFailedLoad();
                        return;
                    }

                    // #1671: persistent STATE_IDLE means the queue was dismissed or
                    // ended out from under us — no content will ever load onto this
                    // factory player.  Recover fast (stop the orphaned outgoing,
                    // keep the coordinator's now-idle player) instead of waiting the
                    // full READY_TIMEOUT_MS.
                    //
                    // Screen-lock reliability guard (sr-shishir, Xiaomi): only fast-recover
                    // when NO loadVideo has been issued for this transition.  A legitimate
                    // auto-advance dwells at STATE_IDLE before STATE_BUFFERING while YTM's
                    // queue-advance issues the load; that dwell was ~210 ms screen-on but
                    // stretched to ~400 ms locked on a Samsung and goes further under MIUI's
                    // background throttling.  The old unconditional 600 ms timer tore down a
                    // valid locked load.  Once onBeforeLoadVideo has fired (pendingLoadIssued),
                    // content IS coming — defer to STATE_READY / the full 10 s timeout instead.
                    if (state == STATE_IDLE && !pendingLoadIssued) {
                        if (pollIdleStreakStartMs == 0) {
                            pollIdleStreakStartMs = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - pollIdleStreakStartMs >= IDLE_LOAD_FAIL_MS) {
                            logInfo(() -> "Pending player stuck IDLE — queue dismissed/ended; "
                                    + "recovering " + dumpState());
                            recoverFromFailedLoad();
                            return;
                        }
                    } else {
                        pollIdleStreakStartMs = 0;
                    }

                    if (state != lastPollState) {
                        logDebug(() -> "Poll: state → " + state);
                        lastPollState = state;
                    }

                    if (System.currentTimeMillis() > deadline) {
                        logError(() -> "Timeout waiting for new track");
                        recoverFromFailedLoad();
                        return;
                    }

                    mainHandler.postDelayed(this, READY_POLL_MS);
                } catch (Exception e) {
                    logError(()-> "Poll error", e);
                    recoverFromFailedLoad();
                }
            }
        }, READY_POLL_MS);
    }

    /**
     * #1671: graceful recovery when the pending factory player never loads
     * content (queue dismissed via 3-dot "Dismiss queue" or swipe-to-dismiss
     * miniplayer; or end-of-queue).  Stops the orphaned outgoing player and any
     * fade-out animations, then resets crossfade state — but crucially does NOT
     * release the pending factory player, because on 9.x the coordinator was
     * already swapped to it ({@code patch_setPlayerWithBindings}) and YTM now
     * owns it as the current (idle) player.  Releasing it would leave the
     * coordinator referencing a dead player, wedging all future playback until
     * the app is force-killed — which was the core #1671 symptom.
     */
    private static void recoverFromFailedLoad() {
        recoverFromFailedLoad(false);
    }

    /**
     * @param stopKeptPlayer when true (a dismiss — see {@link #onQueueDismissed}),
     *        actively pause the kept coordinator player so the dismissed track stops.
     *        The failed-load / end-of-queue recovery passes false (the incoming never
     *        loaded content, so there is nothing audible to pause).
     */
    private static void recoverFromFailedLoad(boolean stopKeptPlayer) {
        cleanupAllPlayers(stopKeptPlayer);
        if (audioModeWasForced) {
            audioModeWasForced = false;
            restoreVideoModeSilently();
        }
    }

    /**
     * #1671: invoked the instant the user dismisses playback — from the stock
     * "Dismiss queue" menu action and the swipe-to-dismiss miniplayer gesture
     * (both hooked in CrossfadePatch).  This is the clean fix: rather than letting
     * the imminent stopVideo(5) masquerade as a manual skip and trigger a phantom
     * crossfade (which we'd then have to recover from), we know up front this is a
     * dismiss.  We open a short suppression window so {@link #onBeforeStopVideo}
     * passes the stop through, and — if a crossfade is already in flight (the user
     * dismissed mid-fade) — tear it down now so nothing keeps playing in the
     * background.
     *
     * <p>Safe to call when crossfade is disabled or no crossfade is active; it
     * simply arms the window (cheap) and returns.
     */
    public static void onQueueDismissed() {
        if (!CROSSFADE_ENABLED) return;
        dismissWindowUntilMs = SystemClock.uptimeMillis() + DISMISS_WINDOW_MS;
        logInfo(() -> "onQueueDismissed — crossfade suppressed for the dismiss stop " + dumpState());
        if (crossfadeInProgress) {
            // Dismiss landed during a crossfade already in flight: stop the orphaned
            // outgoing / fade-out players AND pause the kept coordinator player.  On a
            // dismiss the user expects playback to END, but the kept player is our
            // factory player swapped in at the coordinator level — YTM's dismiss arrives
            // as a stopVideo(5) director-RESET (advance-to-next, not a halt) against an
            // empty queue, so it never stops our player and the dismissed song keeps
            // playing (MrGapi, PR #1773).  stopKeptPlayer=true pauses it ourselves.
            recoverFromFailedLoad(true);
        }
    }

    /**
     * Called when a pending player reaches STATE_READY.
     * Moves the outgoing player(s) to the fade-out list and
     * promotes the pending player to the active crossfade-in role.
     */
    private static void onPendingPlayerReady(ExoPlayerAccess newPlayer) {
        FadeCurve curve = Settings.CROSSFADE_CURVE.get();
        long fadeDuration = getCrossfadeDurationMs();

        boolean trackAlreadyEnded = false;
        ExoPlayerAccess outgoing = pendingOutPlayer;
        if (outgoing != null) {
            if (is9x) {
                // On 9.x the coordinator's UI listener (auge.b:Lcou, in Lcrh.N) was already
                // moved from the outgoing player to the incoming player at crossfade start time
                // (in onBeforeStopVideo). No listener migration needed here.
                // The old player's N set is now empty; it is safe to release after fade-out.
                logDebug(() -> "onPendingPlayerReady (9.x): coordinator listener already migrated at start");
            }

            if (outgoingFadePreStarted) {
                // 9.x auto-advance: fade-out was pre-started at coordinator swap time
                // in onBeforeStopVideo. The outgoing is already in fadingOutPlayers
                // running the full configured fade duration. Skip the re-add and the
                // actualRemaining adjustment — those exist for the legacy path where
                // fade-out only began once the new player reached READY.
                logDebug(() -> "onPendingPlayerReady: outgoing @" + System.identityHashCode(outgoing)
                        + " fade-out already in flight (pre-started at swap time)");
                pendingOutPlayer = null;
                outgoingFadePreStarted = false;
            } else {
                // Match fade-out duration to actual remaining audio on the outgoing track.
                // Used for the legacy path (manual skip, 8.x auto-advance) where the
                // fade-out only begins here. Without this adjustment, the fade-out may
                // start too late, causing the outgoing track to end at non-zero volume.
                long fadeOutDuration = fadeDuration;
                try {
                    long pos = outgoing.patch_getCurrentPosition();
                    long dur = outgoing.patch_getDuration();
                    if (dur > 0 && pos >= 0) {
                        long actualRemaining = dur - pos;
                        logDebug(() -> "onPendingPlayerReady: outgoing remaining=" + actualRemaining
                                                + "ms fadeDuration=" + fadeDuration + "ms");
                        if (actualRemaining <= 0) {
                            // Content only loads after natural track end, so READY always
                            // arrives after the old track has finished. Release silently.
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
        // When the old track already ended before READY (auto-advance gap), snap in
        // quickly rather than doing a slow 3s fade from silence.
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

                // #1549: while audio is routed to a cast/mirror receiver, keep
                // re-polling but never dispatch MEDIA_NEXT — native gapless
                // handles the natural-end transition cleanly for the cast layer.
                // The monitor will pick up again once cast disconnects.
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

                        // #repeat: REPEAT_SINGLE — crossfade the song onto itself instead
                        // of advancing the queue.  MEDIA_NEXT would skip to the next track
                        // (it ignores repeat-one), so take the self-crossfade path: swap to
                        // a fresh player and re-load the SAME song at position 0.  If it
                        // can't engage (no cached descriptor), fall through to MEDIA_NEXT so
                        // the song still loops (just without crossfade).
                        if (repeatSingleActive && is9x) {
                            logInfo(() -> "Auto-advance: REPEAT_SINGLE — crossfading song onto itself");
                            if (startRepeatSelfCrossfade(atad, coordinator, exo)) {
                                return;
                            }
                            logWarn(() -> "REPEAT_SINGLE self-crossfade could not engage — falling back to MEDIA_NEXT");
                        }

                        // Auto-advance trigger: simulated MEDIA_NEXT key event.  Other paths
                        // tested (auih.y direct, atzq.p, atad.stopVideo(5) direct) either no-op
                        // on 9.x or fail to advance the queue.  The MEDIA_NEXT dispatch goes
                        // through YTM's mediasession skip handler which runs the full
                        // queue-advance + clearQueue + loadOnesieVideo chain.  onBeforeStopVideo
                        // intercepts the resulting stopVideo(5) for crossfade setup.
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

    /**
     * #repeat: REPEAT_SINGLE crossfade-onto-self.  Mirrors the 9.x auto-advance swap
     * in {@link #onBeforeStopVideo} (factory player → coordinator, detach cwh, migrate
     * the coordinator listener, re-enable + pre-fade the outgoing), but instead of
     * advancing the queue it re-issues the CURRENT track's cached load descriptor at
     * the top via {@code patch_loadVideoWith} — so the ending instance fades out while
     * a fresh-from-zero instance fades in.  The queue is never touched (correct for
     * repeat-one).  Returns false if it cannot engage (no descriptor / wrong types),
     * so the caller can fall back to MEDIA_NEXT.
     */
    private static boolean startRepeatSelfCrossfade(Object atad,
            PlayerCoordinatorAccess coordinator, ExoPlayerAccess currentExo) {
        if (!is9x) return false;
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
            newExo.patch_setVolume(0.0f);

            pendingOutPlayer = currentExo;
            pendingInPlayer = newExo;
            activeCoordinator = coordinator;
            crossfadeInProgress = true;
            // Treat like an auto-advance crossfade: the outgoing reaches natural end
            // during the fade, so detach cwh to avoid its onEnded mis-routing.  Do NOT
            // set queueAdvancedByMonitor — the queue is intentionally NOT advanced.
            autoAdvanceCrossfadeActive = true;
            deferredSwapStartTime = System.currentTimeMillis();
            try {
                currentExo.patch_detachCwhFromEventDispatch();
            } catch (Exception e) {
                logWarn(() -> "repeat-single: cwh detach on outgoing failed: " + e.getMessage());
            }

            Object coordListener = null;
            try {
                coordListener = coordinator.patch_getCoordinatorListener();
                if (coordListener != null) {
                    currentExo.patch_removeDirectListener(coordListener);
                }
            } catch (Exception e) {
                logWarn(() -> "repeat-single: pre-remove coord listener failed: " + e.getMessage());
            }

            coordinator.patch_setPlayerWithBindings(newExo);
            logDebug(() -> "repeat-single: swapped coordinator → new player @"
                    + System.identityHashCode(newExo));

            if (coordListener != null) {
                try {
                    newExo.patch_addDirectListener(coordListener);
                } catch (Exception e) {
                    logWarn(() -> "repeat-single: re-register coord listener failed: " + e.getMessage());
                }
            }
            VideoSurfaceAccess surface = (VideoSurfaceAccess) coordinator.patch_getVideoSurface();
            if (surface != null) {
                surface.patch_setPlayerReference(newExo);
            }

            // Re-enable the outgoing (ending) instance for an audible fade-out, and
            // pre-start its fade now (auto-advance style) so it reaches silence
            // gracefully regardless of the new player's load latency.
            currentExo.patch_setPlayWhenReady(true);
            currentExo.patch_setVolume(1.0f);
            FadeCurve outCurve = Settings.CROSSFADE_CURVE.get();
            long outFadeDuration = getCrossfadeDurationMs();
            fadingOutPlayers.add(new FadingPlayer(currentExo, outFadeDuration, outCurve));
            outgoingFadePreStarted = true;
            ensureFadingLoopRunning();

            // Load the SAME song from the top onto the new (coordinator) player.
            logInfo(() -> "repeat-single: re-issuing loadVideo (same song) onto @"
                    + System.identityHashCode(newExo) + " descriptor=@"
                    + System.identityHashCode(descriptor));
            ((MedialibPlayerAccess) atad).patch_loadVideoWith(descriptor);

            pollForNewTrackReady(newExo);
            return true;
        } catch (Exception e) {
            logError(() -> "startRepeatSelfCrossfade error", e);
            cleanupAllPlayers();
            return true; // we already mutated state; don't also fire MEDIA_NEXT
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

    /**
     * Fade-in animation for the active crossfade-in player.
     * Fade-outs are managed independently by the fading loop.
     * Self-terminates if this player is superseded by a chained skip.
     */
    private static void animateCrossfade(final ExoPlayerAccess inPlayer, final long durationOverrideMs) {
        // Re-enforce volume=0 before unmuting: for auto-advance, the native
        // playNextInQueue runs after our hook and may reset volume to 1.0.
        try {
            inPlayer.patch_setVolume(0.0f);
        } catch (Exception e) {
            logWarn(()-> "fade-in pre-start: failed to zero volume: " + e.getMessage());
        }

        try { inPlayer.patch_setPlayWhenReady(true); } catch (Exception ignored) {}

        // #1671 follow-up: the crossfade drives the incoming track to play WITHOUT going
        // through MedialibPlayer.playVideo, so the onPlayVideo hook never fires to clear
        // playerIsPlaying. After a swipe-dismiss (which fires onPauseVideo → false), the
        // flag would otherwise stay stale-false for the rest of the process, making the
        // next skip's re-enable check silence the outgoing player → an audible ~0.5s gap
        // until the new track buffers. A force-close "fixed" it only by resetting the
        // process default (true). Mirror what onPlayVideo would do: the incoming player
        // is now the active, audibly-playing track.
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
                        crossfadeInProgress = false;
                        autoAdvanceCrossfadeActive = false;
                        queueAdvancedByMonitor = false;
                        monitorCrossfadeActive = false;
                        crossfadeInPlayer = null;
                        activeCoordinator = null;

                        // Do NOT silently restore video mode here.  Stream loaded by the
                        // crossfade is audio-only, so chxp must stay at audio-preferred to
                        // keep the album-art UI subscriber in sync.  Restoring video would
                        // make YTM's UI swap to the video-player fragment, which has no
                        // stream to render → black box.  User can restore video by long-
                        // pressing to pause crossfade (handled in shouldBlockVideoToggle).
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

    /**
         * InvocationHandler that forwards every method call to a captured real listener.
         * Used by {@link #migrateListeners} to create proxy wrappers around migrated bxi objects.
         *
         * <p>Storing the target in a named field (rather than a lambda capture) lets
         * {@link #unwrapForwardingTarget} recover the original listener and avoid
         * proxy-of-proxy accumulation on consecutive skips.</p>
         */
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

    /**
     * If {@code listener} is a {@link java.lang.reflect.Proxy} backed by a
     * {@link ForwardingHandler}, returns the handler's {@code target} (unwrapping one
     * layer). Recurses until the deepest non-proxy target is reached.
     * Returns {@code listener} unchanged if it is not a forwarding proxy.
     */
    private static Object unwrapForwardingTarget(Object listener) {
        while (Proxy.isProxyClass(listener.getClass())) {
            InvocationHandler h = Proxy.getInvocationHandler(listener);
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
     * {@link CopyOnWriteArraySet#add} always succeeds even when
     * {@code realListener} is already in the set.  ExoPlayer's listener dispatch
     * uses {@code invoke-interface} against the stored Object, so a Proxy that
     * implements the same interfaces receives the call correctly.</p>
     *
     * @return the proxy, or {@code null} if no interfaces are discoverable (should never happen).
     */
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
     * {@link CopyOnWriteArraySet}, causing UI disconnection.
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
     *       {@link CopyOnWriteArraySet}'s equality check, while
     *       ExoPlayer's {@code invoke-interface} dispatch still reaches the real listener.</li>
     *   <li>Fallback: if proxy creation or {@code cau.add} fails for every listener,
     *       copy the original cat objects directly.</li>
     * </ol>
     */
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

            // Snapshot before clearing so we have the original cat objects for the fallback.
            List<Object> catSnapshot = new ArrayList<>(from);

            // Extract real listeners (unwrap any proxy layers from prior skips).
            List<Object> realListeners = new ArrayList<>(catSnapshot.size());
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
            CopyOnWriteArraySet<Object> toSet =
                    (toSetObj instanceof CopyOnWriteArraySet)
                    ? (CopyOnWriteArraySet<Object>) toSetObj : null;
            int toSizeBefore = toSet != null ? toSet.size() : -1;

            // Build the set of real listeners already in the new player so we can skip
            // factory duplicates (they are already registered at construction time).
            Set<Object> alreadyPresent = new HashSet<>();
            if (toSet != null) {
                for (Object cat : new ArrayList<>(toSet)) {
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
                // All proxy creations or cau.add calls failed — fall back:
                // copy the original cat objects directly into the new player's set.
                logWarn(()-> "migrateListeners: proxy path failed — copying " + catSnapshot.size()
                        + " original cats to toPlayer (had " + toSizeBefore + ")");
                if (toSet != null) toSet.addAll(catSnapshot);
            }
        } catch (Exception e) {
            logWarn(()-> "migrateListeners failed", e);
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

        // 9.x: detach coordinator_cwh from the player's crh.h event dispatch set at the last
        // possible moment — just before release(). This prevents the release from firing
        // isPlayingChanged(false) through crh.h → cwh.b → MediaSession (which would show PAUSED
        // even though the new player is already playing). Doing this at release time (not at swap
        // time) preserves normal pause/seek/position events on the active player during crossfade.
        if (is9x) {
            try {
                p.patch_detachCwhFromEventDispatch();
                logDebug(() -> "releasePlayer: 9.x detached cwh from event dispatch on @"
                        + System.identityHashCode(p));
            } catch (Exception e) {
                logDebug(() -> "releasePlayer: 9.x cwh event dispatch detach failed: " + e.getMessage());
            }
        }

        // 9.x: suppress cwh.U() for the duration of this release.
        // crh.P() calls cwh.U() on the SHARED singleton cwh, which asynchronously calls
        // cwh.b.d() via a posted Runnable (cvu.run()). cwh.b.d() clears the CopyOnWriteArraySet
        // that holds auih.k (MediaSession listener) and sets cgd.i=true, preventing future adds.
        // After this, NO player can send events to MediaSession (pause/seek/position all frozen).
        // The injected early-return in cwh.U()V checks suppressCwhU and skips posting the
        // Runnable — preserving cwh.b and all its listeners for the new (incoming) player.
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

    /**
     * Emergency cleanup: releases all tracked players and resets state.
     * Used on errors and when crossfade is disabled/paused.
     */
    private static void cleanupAllPlayers() {
        cleanupAllPlayers(false);
    }

    /**
     * @param stopKeptPlayer when true, pause the kept coordinator player(s) via
     *        {@code patch_setPlayWhenReady(false)} so a dismissed track actually stops.
     *        Default (false) preserves the original behavior for the failed-load /
     *        abort / error teardown paths, which leave the player ready for YTM's next
     *        load.  Volume is restored to 1.0 either way (silent-playback guard), so a
     *        paused-then-reused player is not stuck quiet.
     */
    private static void cleanupAllPlayers(boolean stopKeptPlayer) {
        // logInfo (not logError): cleanup is a routine recovery/reset action — e.g. a
        // queue dismiss or end-of-queue (#1671) — not a user-facing error.  logError
        // routes through printException which shows a toast when "show toast on error"
        // is enabled, so using it here popped a spurious "cleanup" toast on every
        // dismiss.  Genuine error callers already log their own error before calling.
        logInfo(() -> "CLEANUP (reset crossfade state): " + dumpState());
        if (deferredSwapRunnable != null) {
            mainHandler.removeCallbacks(deferredSwapRunnable);
            deferredSwapRunnable = null;
        }
        releaseAllFadingPlayers();
        // #1671: do NOT release pendingInPlayer.  On 9.x the coordinator was
        // already swapped to it (patch_setPlayerWithBindings) before polling
        // began, so YTM owns it as the current player.  Releasing it here is
        // exactly what left the coordinator pointing at a dead player and wedged
        // playback until app-kill.  Drop our reference only; YTM keeps the player
        // (idle, ready to receive the next loadVideo).
        //
        // BUT we zeroed this player's volume at crossfade setup (newExo.setVolume(0))
        // and re-zeroed it on every poll tick.  If we just drop the reference, the
        // coordinator's current player stays at volume 0 — so the next track YTM
        // loads onto it plays silently (AudioTrack runs, position advances, no
        // sound: the "playing but no audio" zombie).  Restore it to full volume so
        // the next playback is audible.
        ExoPlayerAccess pin = pendingInPlayer;
        if (pin != null) {
            try {
                pin.patch_setVolume(1.0f);
                if (stopKeptPlayer) {
                    pin.patch_setPlayWhenReady(false);
                    logInfo(() -> "cleanupAllPlayers: paused kept coordinator player @"
                            + System.identityHashCode(pin) + " (dismiss — stop dismissed track)");
                } else {
                    logInfo(() -> "cleanupAllPlayers: restored kept coordinator player @"
                            + System.identityHashCode(pin) + " volume → 1.0 (#1671 silent-playback guard)");
                }
            } catch (Exception ignored) {}
        }
        pendingInPlayer = null;
        ExoPlayerAccess po = pendingOutPlayer;
        if (po != null) { releasePlayer(po); pendingOutPlayer = null; }
        // #1671: crossfadeInPlayer is the coordinator's current (promoted) player —
        // YTM owns it, so we only drop our ref, never release it.  But if we tear
        // down mid fade-in its volume may be < 1.0; restore it so the next track
        // YTM loads onto it isn't quiet (same silent-playback guard as pendingIn).
        ExoPlayerAccess cip = crossfadeInPlayer;
        if (cip != null && cip != pin) {
            try {
                cip.patch_setVolume(1.0f);
                if (stopKeptPlayer) cip.patch_setPlayWhenReady(false);
            } catch (Exception ignored) {}
        }
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
                    // Defer the release so ExoPlayer's AudioTrack buffer can drain the
                    // now-silent frames before the player is torn down — prevents an
                    // abrupt cut/click when buffered non-zero-volume audio gets discarded.
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

    public static void onActivityStop() {
        activityRunning = false;
        logInfo(() -> "onActivityStop");
        // Do not stop the auto-advance monitor here — crossfade should continue
        // even when the screen locks or the app is minimized (#1311).
        //
        // Do not abort an in-progress crossfade either — YTM is a music app with
        // a foreground service, so playback continues after onStop. The fade
        // animations keep ticking on mainHandler and complete naturally in the
        // background. Aborting here caused the outgoing track to be released
        // mid-fade when the user pressed the power button during a crossfade
        // (#1442).
    }

    /**
     * Called when the MusicActivity is being destroyed (swipe-clear from recents,
     * explicit finish, OS reclaim).  Distinct from {@link #onActivityStop} which
     * also fires on screen-lock and minimize.  The process itself may survive via
     * the foreground service, so we must clean up our static state — otherwise
     * the next activity instance inherits orphaned references to released or
     * unreachable players.
     *
     * <p>We do NOT release {@code crossfadeInPlayer} because it is the active
     * coordinator player which YTM owns and manages across activity recreation.
     * We only release the players we created (factory pendings + fade-outs) and
     * clear our state flags.
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

    public static void onActivityStart() {
        activityRunning = true;
        logInfo(() -> "onActivityStart");

        // Pause is per-session: reset on every activity start so a stale paused state
        // can't survive a process that wasn't actually killed on swipe.
        if (isCrossfadePaused) {
            logDebug(() -> "onActivityStart: auto-resetting isCrossfadePaused to false");
            isCrossfadePaused = false;
        }

        // Proactive attach: hot-path hooks alone leave the handler unattached on a
        // freshly-recreated activity until the user does something else first.
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
    /**
     * One-time-per-process guard for the "crossfade unstable while casting" toast.
     * Static so it fires once per app/listening session (never reset in-process),
     * avoiding repeat toasts on every track transition while cast routing is active.
     */
    private static volatile boolean castUnstableToastShown = false;

    /**
     * #1549: Detect when audio is being routed to a cast/mirror receiver
     * (Chromecast, Samsung audio mirroring, HDMI mirror, etc.). Crossfade is
     * disabled in those scenarios because our coordinator player swap causes
     * MediaSession state thrashing (PAUSED → PLAYING → PAUSED flicker) that
     * the cast/mirror layer forwards to the receiver as PAUSE+PLAY commands.
     * Forgiving receivers (e.g. Google Home Mini) absorb this as a brief
     * audio glitch; stricter ones (e.g. Sony AVRs) treat it as session-end
     * and drop the cast connection entirely.
     *
     * <p>This skip is the defensive fix.  The deeper fix would intercept the
     * MediaSession state writes during our swap so the cast layer never sees
     * the flicker — see future task.
     *
     * <p>Whitelist of device types that trigger the skip: HDMI, HDMI_ARC,
     * HDMI_EARC, IP (network audio), BUS (system bus devices).  Bluetooth A2DP,
     * wired headsets, USB audio, and BLE audio are NOT in this list — those
     * tolerate the swap fine.  TYPE_REMOTE_SUBMIX is also excluded: it is a
     * LOCAL-decode capture (Android Auto projection, screen recording, screen
     * mirroring) where crossfade works correctly — including it disabled
     * crossfade on Android Auto.  True decode-on-receiver cast is still caught
     * by the MediaRouter PLAYBACK_TYPE_REMOTE signal.
     *
     * <p>Returns false on API < 28 (the {@link AudioPlaybackConfiguration#getAudioDeviceInfo()}
     * method isn't available); pre-Pie devices are rare enough that we accept
     * the cast-disconnect risk for them.
     *
     * <p>Cached with a {@value #CAST_CHECK_TTL_MS}ms TTL since this is queried
     * on every crossfade-engagement hook fire (manual skip, auto-advance,
     * monitor tick).
     */
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
                // Primary signal: MediaRouter says the selected audio route is REMOTE.
                // Definition (per Android docs): "the route controls playback on a
                // remote device" — i.e. decoding happens on the receiver, not locally.
                // This is exactly the boundary that matters for #1549: when audio is
                // decoded remotely, the receiver runs its own state machine and our
                // coordinator-swap MediaSession flicker corrupts its session.  When
                // decoding happens locally (built-in speaker, BT A2DP, wired, USB),
                // crossfade works fine — so we LEAVE those alone.
                //
                // MediaRouter is deprecated since API 30 but still
                // works through API 36+.  The newer MediaRouter2 lives in a separate
                // module we'd have to bring in via the patcher classpath; deprecated
                // is the simpler choice here.
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
                // Secondary signal: AudioDeviceInfo-level remote outputs (HDMI mirror,
                // explicit remote-submix, IP audio, system bus).  These are caught even
                // if MediaRouter doesn't reflect the routing.
                if (!casting) {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        // noinspection WrongConstant
                        for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                            int type = info.getType();
                            probe.append("dev{type=").append(type)
                                    .append(",id=").append(info.getId()).append("} ");
                            // NOTE: TYPE_REMOTE_SUBMIX is intentionally NOT in this list.
                            // Android Auto (com.google.android.projection.gearhead) opens a
                            // REMOTE_SUBMIX capture to stream the phone's mixed audio to the
                            // head unit — but decoding stays LOCAL, so the dual-player
                            // crossfade works fine and there is no decode-on-receiver session
                            // to corrupt.  The same is true of screen recording / MediaProjection
                            // / screen-mirror capture.  Including REMOTE_SUBMIX disabled crossfade
                            // on Android Auto (a false positive); genuine cast (decode-on-receiver)
                            // is still caught by the MediaRouter PLAYBACK_TYPE_REMOTE signal above.
                            //
                            // TODO(future): this blanket drop also re-enables crossfade during
                            // non-AA screen/audio mirroring (e.g. Samsung mirroring — the original
                            // #1549 case).  The surgical fix is to detect Android Auto SPECIFICALLY
                            // via the public CarConnection API
                            // (androidx.car.app.connection — query content://androidx.car.app.connection,
                            // column "CarConnectionState", value 2 = CONNECTION_TYPE_PROJECTION) and
                            // exempt only AA, while keeping REMOTE_SUBMIX in the disable list for
                            // other mirror captures.  Deferred because YTM doesn't bundle the
                            // CarConnection client class and the backing provider didn't query
                            // cleanly from a shell — needs an in-app query + fallback verified first.
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
                    + " — crossfade " + (castingFinal ? "disabled" : "re-enabled")
                    + " [" + probe.toString().trim() + "]");
            // One-time-per-session notice: when genuine cast/mirror routing is first
            // detected (decode-on-receiver, or HDMI/IP/BUS), crossfade is disabled.
            // Tell the user once that crossfade over cast/mirror is unstable and may
            // never be fixable, so the silently-disabled crossfade isn't a mystery.
            // (Android Auto no longer trips this — REMOTE_SUBMIX was dropped.)
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

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private static synchronized void toggleSessionPause() {
        isCrossfadePaused = !isCrossfadePaused;
        boolean isPaused = isCrossfadePaused;

        logDebug(() -> "Session " + (isPaused ? "PAUSED" : "RESUMED")
                + " [inVideo=" + isCurrentlyInVideoMode()
                + " inProgress=" + crossfadeInProgress + "]");

        if (isCrossfadePaused) {
            // Suppress abortCrossfadeNow's silent restore; video resync is the user's
            // responsibility via the audio/video toggle (handled in shouldBlockVideoToggle).
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
     * Called from the nba constructor hook so we always have a valid reference
     * to the audio/video toggle object even before any toggle interaction fires.
     * This ensures forceAudioModeIfNeeded() can act on songs loaded from the main
     * feed without requiring the user to trigger an explicit audio/video toggle.
     */
    public static void onNbaCreated(Object nba) {
        lastNbaRef = new WeakReference<>(nba);
        logDebug(() -> "onNbaCreated: nba captured @" + System.identityHashCode(nba)
                + " class=" + nba.getClass().getSimpleName());
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

            logDebug(() -> "videoToggle: isAudioMode=" + isAudioMode
                    + " enabled=" + isEnabled() + " paused=" + isCrossfadePaused
                    + " inVideoMode(before)=" + inVideoMode);

            if (!isEnabled()) {
                logDebug(() -> "videoToggle → ALLOW (crossfade disabled)");
                return false;
            }

            // Paused: audio→video must go through patch_restoreVideoMode (broadcast).  The
            // natural toggle's broadcast can no-op at subscriber level because prior silent
            // forceAudioModeSilent left subscribers' cached state out of sync with chxp.
            // Video→audio direction works via the natural toggle.
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

            // Active crossfade: block audio→video, allow video→audio.
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


    /**
     * Walks the delegate chain from the last known atad instance looking for a
     * {@link VideoToggleAccess} node (the nba class). Updates {@link #lastNbaRef} on success.
     *
     * <p>This is a fallback for sessions where {@link #shouldBlockVideoToggle} was never called
     * (e.g., songs loaded from the main feed without any audio/video toggle interaction).
     * Returns the nba object, or null if not found in the chain.</p>
     */
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

    /**
     * Broadcast variant of forceAudioModeIfNeeded — fires the full nmi reactive
     * broadcast so all YTM subscribers (MediaView, album-art ImageView, content-mode
     * UI) reconcile their cached state.  Use this when crossfade is becoming active
     * for the user's session (onPlayVideo) so the UI properly switches to album-art
     * display.  Cost: brief stream reload to the audio-only stream.  Cannot be used
     * during the crossfade SWAP itself (onBeforeStopVideo / onBeforePlayNext) because
     * the broadcast triggers an nmi-driven stopVideo(5) jump that would re-enter our
     * own hook and corrupt the swap-in-progress.
     */
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

    /** Tracks the currently-registered global layout listener so we can remove it. */
    private static android.view.ViewTreeObserver.OnGlobalLayoutListener longPressLayoutListener;
    private static WeakReference<View> longPressLayoutListenerHost = new WeakReference<>(null);

    /** Debounce: skip duplicate posts when hot-path hooks fire in rapid succession. */
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

    /**
     * Walks the current activity's decor view for the shuffle button and attaches the
     * long-press handler. Returns true if any attachments were made.
     */
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

    /**
     * Registers a global layout listener on the activity's decor view that re-attaches
     * the long-press handler on every layout pass (does not self-remove — YTM's reactive
     * UI may rebind handlers at any time).
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

        // setOnLongClickListener coexists with setOnTouchListener / setOnClickListener,
        // so YTM's normal shuffle-tap keeps working and our handler isn't wiped by YTM's
        // touch-handler rebinds.
        btn.setOnLongClickListener(v -> {
            toggleSessionPause();
            logDebug(() -> "Shuffle long-press fired on " + tag + "@" + viewId);
            return true;
        });
        btn.setLongClickable(true);
    }

}

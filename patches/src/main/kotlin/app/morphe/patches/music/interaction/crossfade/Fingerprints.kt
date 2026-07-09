/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1065
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * Crossfade discovery uses two tiers of fingerprints:
 *
 * 1. **Anchor fingerprints** — unique log/error strings that resolve stable classes and hook sites.
 * 2. **Execute-time fingerprints** — inline [Fingerprint] instances in `crossfadePatch` for types
 *    only known after anchors resolve.
 *
 * Three method discoveries (`getPlaybackState`, `getDuration`, `getCurrentPosition`) use manual
 * hierarchy-walking instead of `Fingerprint(definingClass=...)` because the methods may be
 * defined on a superclass of the ExoPlayer impl, not on the impl itself.
 */

/** Medialib outer player (atad): `stopVideo`. */
internal object StopVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("stopVideo", "MedialibPlayer.stopVideo")
)

/** Inner coordinator (athu): `playNextInQueue` / gapless. */
internal object PlayNextInQueueFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IGET_OBJECT)
    ),
    strings = listOf("gapless.seek.next", "playNextInQueue.")
)

/** Audio/video toggle button class (nba). */
internal object AudioVideoToggleFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to update user last selected audio")
)

internal object PauseVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("pauseVideo", "MedialibPlayer.pauseVideo()")
)

internal object PlayVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("playVideo", "MedialibPlayer.playVideo()")
)

/**
 * ExoPlayer concrete implementation (cpp) — unique "ExoPlayerImpl" log tag.
 * Must also check that the class implements ExoPlayer, because a synthetic Runnable
 * (coz) also references "ExoPlayerImpl" as a log tag.
 */
internal object ExoPlayerImplFingerprint : Fingerprint(
    strings = listOf("ExoPlayerImpl"),
    custom = { _, classDef ->
        classDef.interfaces.contains("Landroidx/media3/exoplayer/ExoPlayer;")
    }
)

/** MedialibPlayer loadVideo method (atzq.o). Scoped to StopVideoFingerprint class. */
internal object LoadVideoFingerprint : Fingerprint(
    classFingerprint = StopVideoFingerprint,
    returnType = "V",
    strings = listOf("MedialibPlayer.loadVideo(")
)

/**
 * REPEAT_SINGLE detection: MediaSessionLoopStateAdapter (kyb.a(Ljava/lang/Object;)V).
 * This adapter converts YTM's loop-state enum (Lnwu: LOOP_OFF=0, LOOP_ALL=1,
 * LOOP_ONE=2, LOOP_DISABLED=3) into the Android MediaSession repeat int and pushes
 * it to the session — so it fires on every loop-state change (and init).  Hooking
 * its entry lets the crossfade manager track the live repeat mode (the native LOCAL
 * repeat state isn't reachable from the player classes the patch holds).  Anchored
 * by the globally-unique log string in this method.
 */
internal object LoopStateAdapterFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("attempted to update repeat mode but media session was null")
)

/**
 * #1671: the DismissWatchEvent handler (iqr.handleDismissWatchEvent) — the single
 * point that processes a watch-page / queue dismissal, regardless of source (stock
 * "Dismiss queue" menu, swipe-to-dismiss miniplayer).  A normal skip never posts a
 * DismissWatchEvent, so this is dismiss-UNIQUE (unlike clearQueue, which also runs
 * in the normal skip's queue-advance chain).  The handler is anchored by its call
 * to the UNobfuscated WatchWhileLayout class plus the preserved handler name.
 *
 * If this ever fails to match on a future build, crossfade still works — the dismiss
 * just falls back to the slower poll-STATE_IDLE recovery in pollForNewTrackReady.
 */
internal object HandleDismissWatchEventFingerprint : Fingerprint(
    name = "handleDismissWatchEvent",
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lcom/google/android/apps/youtube/music/watchpage/ui/WatchWhileLayout;",
        )
    ),
    custom = { method, _ ->
        method.parameterTypes.size == 1
    }
)

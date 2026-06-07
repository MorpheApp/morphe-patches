/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
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
    strings = listOf("stopVideo", "MedialibPlayer.stopVideo"),
)

/** Inner coordinator (athu): `playNextInQueue` / gapless. */
internal object PlayNextInQueueFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
    ),
    strings = listOf("gapless.seek.next", "playNextInQueue."),
)

/** Audio/video toggle button class (nba). */
internal object AudioVideoToggleFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to update user last selected audio"),
)

internal object PauseVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("pauseVideo", "MedialibPlayer.pauseVideo()"),
)

internal object PlayVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("playVideo", "MedialibPlayer.playVideo()"),
)

/**
 * ExoPlayer concrete implementation (cpp) — unique "ExoPlayerImpl" log tag.
 * Must also check that the class implements ExoPlayer, because a synthetic Runnable
 * (coz) also references "ExoPlayerImpl" as a log tag.
 */
internal object ExoPlayerImplFingerprint : Fingerprint(
    strings = listOf("ExoPlayerImpl"),
    custom = { _, classDef ->
        classDef.interfaces.any { it == "Landroidx/media3/exoplayer/ExoPlayer;" }
    },
)

/** MedialibPlayer loadVideo method (atzq.o). Scoped to StopVideoFingerprint class. */
internal object LoadVideoFingerprint : Fingerprint(
    classFingerprint = StopVideoFingerprint,
    returnType = "V",
    strings = listOf("MedialibPlayer.loadVideo("),
)

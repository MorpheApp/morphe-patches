package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Crossfade discovery uses three tiers of fingerprints:
 *
 * 1. **Anchor fingerprints** — unique log/error strings that resolve stable classes and hook sites.
 * 2. **Scoped fingerprints** — use [classFingerprint] so resolution stays on a known class.
 * 3. **Execute-time fingerprints** — inline [Fingerprint] instances in `crossfadePatch` for types
 *    only known after anchors resolve.
 *
 * Three method discoveries (`getPlaybackState`, `getDuration`, `getCurrentPosition`) use manual
 * hierarchy-walking instead of `Fingerprint(definingClass=...)` because the methods may be
 * defined on a superclass of the ExoPlayer impl, not on the impl itself.
 */

// =====================================================================
//  Tier 1 — Anchor fingerprints (unique strings, match exactly one class)
// =====================================================================

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

// =====================================================================
//  Tier 2 — Scoped fingerprints (classFingerprint narrows to a known class)
// =====================================================================

/**
 * Medialib outer `playNextInQueue()V` forwarding into the inner coordinator.
 * Scoped to [StopVideoFingerprint] (atad) so we match the outer method, not athu's.
 */
internal object MedialibPlayNextInQueueMethodFingerprint : Fingerprint(
    classFingerprint = StopVideoFingerprint,
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("playNextInQueue"),
)

// =====================================================================
//  Tier 3 — Structural fingerprints (superclass / interface / custom)
// =====================================================================

/**
 * Listener wrapper class (cau) — the wrapper around CopyOnWriteArraySet on the ExoPlayer impl.
 * Matched by having a CopyOnWriteArraySet field and NOT being ExoPlayer itself.
 */
internal object ListenerWrapperClassFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    custom = { _, classDef ->
        !classDef.type.contains("ExoPlayer") &&
            classDef.fields.any { it.type == "Ljava/util/concurrent/CopyOnWriteArraySet;" } &&
            classDef.fields.count() in 2..6
    },
)

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

internal object StopVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("stopVideo", "MedialibPlayer.stopVideo")
)

internal object PlayNextInQueueFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IGET_OBJECT)
    ),
    strings = listOf("gapless.seek.next", "playNextInQueue.")
)

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
 * A synthetic Runnable also logs with the "ExoPlayerImpl" tag, so the interface check is required.
 */
internal object ExoPlayerImplFingerprint : Fingerprint(
    strings = listOf("ExoPlayerImpl"),
    custom = { _, classDef ->
        classDef.interfaces.contains("Landroidx/media3/exoplayer/ExoPlayer;")
    }
)

internal object LoadVideoFingerprint : Fingerprint(
    classFingerprint = StopVideoFingerprint,
    returnType = "V",
    strings = listOf("MedialibPlayer.loadVideo(")
)

/**
 * MediaSession loop-state adapter. It runs on every repeat mode change, which is the only
 * place the live repeat mode can be read from.
 */
internal object LoopStateAdapterFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("attempted to update repeat mode but media session was null")
)

/**
 * DismissWatchEvent handler. Unlike clearQueue it never runs on a normal skip, so it tells
 * a queue dismissal apart from a track change (#1671).
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

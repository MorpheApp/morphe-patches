package app.morphe.patches.music.interaction.crossfade

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

internal object StopVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("stopVideo", "MedialibPlayer.stopVideo"),
)

internal object PlayNextInQueueFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
    ),
    strings = listOf("gapless.seek.next", "playNextInQueue."),
)

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

internal object ExoPlayerImplFingerprint : Fingerprint(
    strings = listOf("ExoPlayerImpl"),
    custom = { _, classDef ->
        classDef.interfaces.any { it == "Landroidx/media3/exoplayer/ExoPlayer;" }
    },
)

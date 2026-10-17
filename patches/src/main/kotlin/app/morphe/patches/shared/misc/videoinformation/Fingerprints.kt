package app.morphe.patches.shared.misc.videoinformation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object PlayerControllerSetTimeReferenceFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT_RANGE,
            name = "<init>",
            parameters = listOf("J", "J", "J", "J", "J", "J", "J", "Z", "Ljava/lang/String;")
        ),
        string("Media progress reported outside media playback: ", comparison = StringComparisonType.CONTAINS)
    )
)

/**
 * Matches method {androidx.media3.exoplayer.ExoPlayerImpl.setPlaybackParameters(PlaybackParameters p1)}
 *
 * @param playbackParametersType The PlaybackParameters type, obtained from [PlaybackParametersToStringFingerprint].
 */
internal fun getPlaybackParametersSetterFingerprint(playbackParametersType: String) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(playbackParametersType),
    custom = { methodDef, classDef ->
        methodDef.implementation != null
            && classDef.interfaces.contains("Landroidx/media3/exoplayer/ExoPlayer;")
    }
) {}

internal fun getExoPlayerImplFingerprint(playbackParametersType: String) = object : Fingerprint(
    classFingerprint = getPlaybackParametersSetterFingerprint(playbackParametersType),
    name = "<init>",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
) {}

/**
 * Matches method {androidx.media3.common.PlaybackParameters}.toString()
 */
internal object PlaybackParametersToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET, type = "F"),
        fieldAccess(opcode = Opcode.IGET, type = "F")
    ),
    strings = listOf(
        "PlaybackParameters(speed=%.2f, pitch=%.2f)"
    )
)

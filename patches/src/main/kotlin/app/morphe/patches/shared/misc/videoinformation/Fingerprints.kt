package app.morphe.patches.shared.misc.videoinformation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

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
    interfaces = listOf("Landroidx/media3/exoplayer/ExoPlayer;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(playbackParametersType)
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

/**
 * @return The SEEK_SOURCE_UNKNOWN constant of the seek source enum.
 * The enum fields are obfuscated, but its static initializer keeps the constant names as strings.
 */
context(patchContext: BytecodePatchContext)
internal fun seekSourceUnknownField(seekSourceEnumType: String): FieldReference {
    val staticInitializer = patchContext.classDefBy(seekSourceEnumType).methods.first { it.name == "<clinit>" }
    val nameIndex = staticInitializer.indexOfFirstStringInstructionOrThrow("SEEK_SOURCE_UNKNOWN")
    val fieldIndex = staticInitializer.indexOfFirstInstructionOrThrow(nameIndex, Opcode.SPUT_OBJECT)

    return staticInitializer.getInstruction(fieldIndex).getReference<FieldReference>()!!
}

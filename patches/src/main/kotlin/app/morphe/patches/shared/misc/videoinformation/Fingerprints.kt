package app.morphe.patches.shared.misc.videoinformation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.anyInstruction
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
        anyInstruction(
            string("Media progress reported outside media playback: "),
            string("Media progress reported outside media playback: %s") // 21.20+
        )
    )
)

// Class name is un-obfuscated in targets before 21.01
internal object VideoQualityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "I", // Resolution.
        "L",
        "Ljava/lang/String;", // Human-readable resolution: "480p", "1080p Premium", etc
        "Z",
        "L"
    )
)

/**
 * Matches method {androidx.media3.exoplayer.ExoPlayerImpl.setPlaybackParameters(PlaybackParameters p1)}.
 * The PlaybackParameters type is obtained from [PlaybackParametersToStringFingerprint].
 */
internal object PlaybackParametersSetterFingerprint : Fingerprint(
    interfaces = listOf("Landroidx/media3/exoplayer/ExoPlayer;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = { listOf(PlaybackParametersToStringFingerprint.originalClassDef.type) }
)

internal object ExoPlayerImplConstructorFingerprint : Fingerprint(
    classFingerprint = PlaybackParametersSetterFingerprint,
    name = "<init>",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

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
internal fun seekSourceUnknownField(seekSourceEnumType: String): FieldReference {
    val staticInitializer = classDefBy(seekSourceEnumType).methods.first { it.name == "<clinit>" }
    val nameIndex = staticInitializer.indexOfFirstStringInstructionOrThrow("SEEK_SOURCE_UNKNOWN")
    val fieldIndex = staticInitializer.indexOfFirstInstructionOrThrow(nameIndex, Opcode.SPUT_OBJECT)

    return staticInitializer.getInstruction(fieldIndex).getReference<FieldReference>()!!
}

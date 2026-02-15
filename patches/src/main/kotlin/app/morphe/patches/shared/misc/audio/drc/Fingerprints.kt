package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches class found in [FormatStreamModelConstructorFingerprint].
 */
internal object CompressionRatioFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lj$/util/Optional;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this"
        ),
        opcode(Opcode.IF_EQZ),
        fieldAccess(
            opcode = Opcode.IGET,
            type = "F",
            location = MatchAfterWithin(3)
        ),
        opcode(Opcode.NEG_FLOAT),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Ljava/lang/Math;->min(FF)F"
        )
    )
)

/**
 * com.google.android.libraries.youtube.innertube.model.media.FormatStreamModel
 * Class names have been obfuscated in the latest YouTube or YouTube Music.
 */
internal object FormatStreamModelConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        literal(45374643L)
    )
)

internal object VolumeNormalizationConfigFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        literal(45425391L)
    )
)
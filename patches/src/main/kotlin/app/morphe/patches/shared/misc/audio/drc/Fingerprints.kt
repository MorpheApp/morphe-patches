package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * com.google.android.libraries.youtube.innertube.model.media.FormatStreamModel
 * Class names have been obfuscated in the latest YouTube or YouTube Music.
 */
object FormatStreamModelConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        literal(45374643L)
    )
)

internal object CompressionRatioFingerprint : Fingerprint(
    classFingerprint = FormatStreamModelConstructorFingerprint,
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

// TY 21.18 and lower, Music 9.18 and lower.
internal object VolumeNormalizationConfigLegacyFingerprint : Fingerprint(
    filters = listOf(
        literal(45425391L)
    )
)

// TY 21.19+, Music 9.19+
internal object VolumeNormalizationConfigFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45784793L)
    )
)

// TY 21.19+, Music 9.20+
internal object OptionalVolumeNormalizationConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45785775L),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterWithin(2)),

        methodCall($$"Lj$/util/Optional;->isEmpty()Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        literal(45783491L, location = MatchAfterWithin(5)),
    )
)

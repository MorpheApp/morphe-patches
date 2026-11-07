package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SeekTypeEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "SEEK_SOURCE_SEEK_TO_NEXT_CHAPTER",
        "SEEK_SOURCE_SEEK_TO_PREVIOUS_CHAPTER"
    )
)

internal object DoubleTapInfoCtorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Landroid/view/MotionEvent;",
        "I",
        "Z",
        "Lj$/time/Duration;"
    )
)

/**
 * A/B flag that turns off the two-finger double tap chapter skip.
 */
internal object DoubleTapChapterSkipFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45767151L)
    )
)

/**
 * Where the two-finger double tap chapter skip is turned on: a server config value
 * (and on 21.11+ an A/B flag that turns it off, see [DoubleTapChapterSkipFeatureFlagFingerprint]).
 */
internal object DoubleTapChapterSkipConfigFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, type = "Z"),
        opcode(opcode = Opcode.CONST_4, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, type = "Z", location = MatchAfterWithin(10))
    ),
    custom = { method, _ -> method.parameterTypes.getOrNull(2) == "Landroid/os/Handler;" }
)

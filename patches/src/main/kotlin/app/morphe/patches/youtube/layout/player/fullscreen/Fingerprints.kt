package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * 19.46+
 */
internal object OpenVideosFullscreenPortraitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Lj\$/util/Optional;"),
    filters = listOf(
        opcode(Opcode.MOVE_RESULT), // Conditional check to modify.
        // Open videos fullscreen portrait feature flag.
        literal(45666112L, location = InstructionLocation.MatchAfterWithin(5)), // Cannot be more than 5.
        opcode(Opcode.MOVE_RESULT, location = InstructionLocation.MatchAfterWithin(10)),
    )
)

/**
 * Pre 19.46.
 */
internal object OpenVideosFullscreenPortraitLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Lj\$/util/Optional;"),
    filters = opcodesToFilters(
        Opcode.GOTO,
        Opcode.SGET_OBJECT,
        Opcode.GOTO,
        Opcode.SGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQ,
        Opcode.IF_EQ,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT  // Conditional check to modify.
    )
)

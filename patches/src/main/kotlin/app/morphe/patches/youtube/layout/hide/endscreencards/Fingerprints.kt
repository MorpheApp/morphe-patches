package app.morphe.patches.youtube.layout.hide.endscreencards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object LayoutCircleFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Landroid/view/View;",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST,
        Opcode.CONST_4,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
    ),
    custom = customLiteral { layoutCircle }
)

internal object LayoutIconFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Landroid/view/View;",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,

        ),
    custom = customLiteral { layoutIcon }
)

internal object LayoutVideoFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    returnType = "Landroid/view/View;",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST,
        Opcode.CONST_4,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
    ),
    custom = customLiteral { layoutVideo }
)

internal object ShowEndscreenCardsParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[L",
    parameters = listOf("L"),
    filters = listOf(
        literal(3, location = MatchFirst()),
        opcode(Opcode.NEW_ARRAY, location = MatchAfterImmediately()),
        literal(1024L, location = MatchAfterWithin(12)),
        literal(1, location = MatchAfterWithin(12)),
        anyInstruction(
            // 20.x
            literal(15, location = MatchAfterWithin(12)),
            // 21.x+
            literal(4, location = MatchAfterWithin(12)),
        )
    ),
    custom = { _, classDef ->
        classDef.methods.count() == 5
    }
)

/**
 * Matches to the class found in [ShowEndscreenCardsParentFingerprint].
 */
internal object ShowEndscreenCardsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(5),
        literal(8),
        literal(9)
    )
)
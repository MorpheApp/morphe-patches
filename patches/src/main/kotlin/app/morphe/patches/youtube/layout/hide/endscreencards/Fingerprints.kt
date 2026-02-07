package app.morphe.patches.youtube.layout.hide.endscreencards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
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
    custom = customLiteral { layoutCircle } // TODO: Convert this to an instruction filter
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
    custom = customLiteral { layoutIcon } // TODO: Convert this to an instruction filter
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
    custom = customLiteral { layoutVideo } // TODO: Convert this to an instruction filter
)

internal object ShowEndscreenCardsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "ordinal",
            location = MatchAfterWithin(7)
        ),
        literal(5),
        literal(8),
        literal(9)
    ),
    custom = { method, classDef ->
        classDef.methods.count() == 5
                // 'public final' or 'final'
                && AccessFlags.FINAL.isSet(method.accessFlags)
    }
)

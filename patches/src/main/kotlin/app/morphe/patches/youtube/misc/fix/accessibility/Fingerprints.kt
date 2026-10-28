package app.morphe.patches.youtube.misc.fix.accessibility

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

private const val BOTTOM_UI_CONTAINER =
    "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;"

internal object BottomUiKeyboardVisibilityFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(45731499L),
        opcode(Opcode.IGET_BOOLEAN, MatchAfterWithin(10)),
        opcode(Opcode.IF_EQ, MatchAfterWithin(2)),
        opcode(Opcode.MOVE, MatchAfterImmediately()),
        opcode(Opcode.GOTO, MatchAfterImmediately()),
        literal(8, opcodes = listOf(Opcode.CONST_16), MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "$BOTTOM_UI_CONTAINER->setVisibility(I)V",
            location = MatchAfterImmediately()
        )
    )
)

internal object BottomUiVisibilityGateFingerprint : Fingerprint(
    definingClass = BOTTOM_UI_CONTAINER,
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(smali = "$BOTTOM_UI_CONTAINER->setVisibility(I)V")
    )
)

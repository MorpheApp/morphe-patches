package app.morphe.patches.youtube.layout.shortsdoubletaptolike

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import com.android.tools.smali.dexlib2.Opcode

internal object ShortsPlayerOnTouchEventLogicFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.CONST_4,
        Opcode.IF_EQZ,
        Opcode.IGET_OBJECT,
        Opcode.IF_EQZ,
        Opcode.NEW_INSTANCE,
        Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_INTERFACE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.IGET,
        Opcode.IPUT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.CONST_4
    )
)
package app.morphe.patches.music.layout.compactheader

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.Opcode

internal object ChipCloudFingerprint : Fingerprint(
    returnType = "V",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST,
        Opcode.CONST_4,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT
    ),
    custom = customLiteral { chipCloud }
)

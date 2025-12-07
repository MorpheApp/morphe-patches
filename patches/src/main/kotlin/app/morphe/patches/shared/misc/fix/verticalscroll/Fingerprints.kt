package app.morphe.patches.shared.misc.fix.verticalscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CanScrollVerticallyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.MOVE_RESULT,
        Opcode.RETURN,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT
    ),
    custom = { _, classDef -> classDef.endsWith("SwipeRefreshLayout;") }
)
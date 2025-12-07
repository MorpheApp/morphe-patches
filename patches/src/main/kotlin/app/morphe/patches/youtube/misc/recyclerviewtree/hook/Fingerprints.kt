package app.morphe.patches.youtube.misc.recyclerviewtree.hook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val recyclerViewTreeObserverFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CHECK_CAST,
        Opcode.NEW_INSTANCE,
        Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.NEW_INSTANCE,
    ),
    strings = listOf("LithoRVSLCBinder")
)

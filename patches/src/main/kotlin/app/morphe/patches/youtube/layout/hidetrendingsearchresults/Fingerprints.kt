package app.morphe.patches.youtube.layout.hidetrendingsearchresults

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SearchBoxTypingMethodFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    custom = customLiteral { suggestionCategoryDividerHeight }
)

internal object SearchBoxTypingStringFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.NEW_INSTANCE,
    ),
)

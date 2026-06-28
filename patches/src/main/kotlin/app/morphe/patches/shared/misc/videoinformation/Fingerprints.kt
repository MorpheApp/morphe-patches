package app.morphe.patches.shared.misc.videoinformation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import com.android.tools.smali.dexlib2.Opcode

internal object PlayerControllerSetTimeReferenceFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_DIRECT_RANGE,
        Opcode.IGET_OBJECT
    ),
    strings = listOf("Media progress reported outside media playback: ")
)

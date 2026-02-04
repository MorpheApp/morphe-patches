package app.morphe.patches.youtube.misc.fix.playbackspeed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * This method is usually used to set the initial speed (1.0x) when playback starts from the feed.
 * For some reason, in the latest YouTube, it is invoked even after the video has already started.
 */
internal object PlaybackSpeedInFeedsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET,
        Opcode.MUL_INT_LIT16,
        Opcode.IGET_WIDE,
        Opcode.CONST_WIDE_16,
        Opcode.CMP_LONG,
        Opcode.IF_EQZ,
        Opcode.IF_LEZ,
        Opcode.SUB_LONG_2ADDR,
    ),
    custom = { method, _ ->
        // TODO: Change this to an instruction filter.
        indexOfGetPlaybackSpeedInstruction(method) >= 0
    }
)

internal fun indexOfGetPlaybackSpeedInstruction(method: Method) =
    method.indexOfFirstInstructionReversed {
        opcode == Opcode.IGET &&
                getReference<FieldReference>()?.type == "F"
    }

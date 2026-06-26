package app.morphe.patches.youtube.interaction.hapticfeedback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MarkerHapticsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to execute markers haptics vibrate.")
)

internal object ScrubbingHapticsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to haptics vibrate for fine scrubbing.")
)

internal object SeekUndoHapticsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to execute seek undo haptics vibrate.")
)

internal object TapAndHoldHapticsHandlerFingerprint : Fingerprint(
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/os/Handler;"),
    filters = listOf(
        string("vibrator"),
        checkCast("Landroid/os/Vibrator;"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/Object;",
            location = MatchAfterImmediately()
        )
    )
)

internal object ZoomHapticsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to haptics vibrate for video zoom")
)

package app.morphe.patches.youtube.interaction.hapticfeedback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

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

/**
 * Matches the method handling tap and hold haptics.
 * Caller must supply the dynamically resolved vibrator field from [TapAndHoldHapticsHandlerFingerprint].
 */
internal fun tapAndHoldHapticsFingerprint(vibratorField: FieldReference) = Fingerprint(
    name = "run",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            reference = vibratorField,
        ),
        checkCast("Landroid/os/Vibrator;"),
        string("Failed to easy seek haptics vibrate.")
    )
)

/**
 * Matches the Vibrator.vibrate() methods.
 * Caller must supply the method parameters and the extension class to exclude.
 */
internal fun vibratorMethodFingerprint(
    vibratorParameters: List<String>,
    extensionClassType: String) = Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/os/Vibrator;",
            name = "vibrate",
            parameters = vibratorParameters,
            returnType = "V"
        )
    ),
    custom = { _, classDef ->
        classDef.type != extensionClassType
    }
)

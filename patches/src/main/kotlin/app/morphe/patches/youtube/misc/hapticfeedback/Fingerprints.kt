package app.morphe.patches.youtube.misc.hapticfeedback

import app.morphe.patcher.Fingerprint

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

internal object ZoomHapticsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Failed to haptics vibrate for video zoom")
)

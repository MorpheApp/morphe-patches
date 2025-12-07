package app.morphe.patches.youtube.misc.hapticfeedback

import app.morphe.patcher.Fingerprint

internal val markerHapticsFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("Failed to execute markers haptics vibrate.")
)

internal val scrubbingHapticsFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("Failed to haptics vibrate for fine scrubbing.")
)

internal val seekUndoHapticsFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("Failed to execute seek undo haptics vibrate.")
)

internal val zoomHapticsFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("Failed to haptics vibrate for video zoom")
)

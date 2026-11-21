package app.morphe.patches.youtube.misc.spoof.dimensions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * The sizes the hardware video decoder supports. The stream selection drops the streams outside of them.
 */
internal object DeviceDimensionsModelToStringFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("minh."),
        string(";maxh.")
    )
)

internal object DeviceDimensionsModelConstructorFingerprint : Fingerprint(
    classFingerprint = DeviceDimensionsModelToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "I", // Min height.
        "I", // Max height.
        "I", // Min width.
        "I"  // Max width.
    )
)

package app.morphe.patches.youtube.misc.dimensions.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object DeviceDimensionsModelToStringFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("minh."),
        string(";maxh.")
    )
)

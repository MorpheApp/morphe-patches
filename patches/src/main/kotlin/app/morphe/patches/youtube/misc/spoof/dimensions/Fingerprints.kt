package app.morphe.patches.youtube.misc.spoof.dimensions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object DeviceDimensionsModelToStringFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("minh."),
        string(";maxh.")
    )
)

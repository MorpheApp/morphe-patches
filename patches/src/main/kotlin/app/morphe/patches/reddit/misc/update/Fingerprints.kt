package app.morphe.patches.reddit.misc.update

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object GooglePlayUpdateCheckFingerprint : Fingerprint(
    filters = listOf(
        string("getAppUpdateInfo(...)")
    )
)

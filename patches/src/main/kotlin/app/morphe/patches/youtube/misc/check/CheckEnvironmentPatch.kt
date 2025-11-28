package app.morphe.patches.youtube.misc.check

import app.morphe.patches.shared.misc.checks.checkEnvironmentPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.mainActivityOnCreateFingerprint

internal val checkEnvironmentPatch = checkEnvironmentPatch(
    mainActivityOnCreateFingerprint = mainActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    "com.google.android.youtube",
)

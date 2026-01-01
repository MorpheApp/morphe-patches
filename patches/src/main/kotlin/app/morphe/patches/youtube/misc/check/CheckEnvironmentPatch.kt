package app.morphe.patches.youtube.misc.check

import app.morphe.patches.shared.misc.checks.checkEnvironmentPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

internal val checkEnvironmentPatch = checkEnvironmentPatch(
    mainActivityOnCreateFingerprint = YouTubeActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    "com.google.android.youtube",
)

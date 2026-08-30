package app.morphe.patches.youtube.misc.extension.hooks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patches.all.misc.extension.ExtensionHook
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

internal object YouTubeApplicationInitFingerprint : Fingerprint(
    // Does _not_ resolve to the YouTube main activity.
    // Required as some hooked code runs before the main activity is launched.
    filters = listOf(
        string("Application.onCreate"),
        string("Application creation")
    )
)

internal val applicationInitHook = ExtensionHook(YouTubeApplicationInitFingerprint)
internal val applicationInitOnCreateHook = ExtensionHook(YouTubeActivityOnCreateFingerprint)

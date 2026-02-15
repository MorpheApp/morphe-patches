package app.morphe.patches.youtube.misc.extension.hooks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.extension.ExtensionHook
import app.morphe.patches.shared.misc.extension.activityOnCreateExtensionHook
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE

internal object YouTubeApplicationInitFingerprint : Fingerprint(
    // Does _not_ resolve to the YouTube main activity.
    // Required as some hooked code runs before the main activity is launched.
    filters = listOf(
        string("Application.onCreate"),
        string("Application creation")
    )
)

/**
 * Hooks the context when the app is launched as a regular application (and is not an embedded video playback).
 */
// Extension context is the Activity itself.
internal val applicationInitHook = ExtensionHook(YouTubeApplicationInitFingerprint)

internal val applicationInitOnCrateHook = activityOnCreateExtensionHook(
    YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
)

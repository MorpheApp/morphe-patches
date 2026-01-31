package app.morphe.patches.reddit.utils.extension.hooks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patches.shared.misc.extension.ExtensionHook

internal val redditActivityOnCreateFingerprint = Fingerprint(
    definingClass = "/FrontpageApplication;",
    name = "onCreate"
)

internal val applicationInitHook = ExtensionHook(
    fingerprint = redditActivityOnCreateFingerprint
)

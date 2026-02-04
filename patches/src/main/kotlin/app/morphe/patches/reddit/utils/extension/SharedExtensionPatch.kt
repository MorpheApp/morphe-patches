package app.morphe.patches.reddit.utils.extension

import app.morphe.patches.reddit.utils.extension.hooks.redditActivityOnCreateFingerprint
import app.morphe.patches.reddit.utils.extension.hooks.redditMainActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.extension.ExtensionHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    "reddit",
    ExtensionHook(fingerprint = redditActivityOnCreateFingerprint),
    ExtensionHook(fingerprint = redditMainActivityOnCreateFingerprint)
)

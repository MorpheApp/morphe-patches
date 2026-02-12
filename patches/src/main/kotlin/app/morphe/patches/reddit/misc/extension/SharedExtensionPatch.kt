package app.morphe.patches.reddit.misc.extension

import app.morphe.patches.reddit.misc.extension.hooks.redditActivityOnCreateFingerprint
import app.morphe.patches.reddit.misc.extension.hooks.redditMainActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.extension.ExtensionHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    "reddit",
    ExtensionHook(fingerprint = redditActivityOnCreateFingerprint),
    ExtensionHook(fingerprint = redditMainActivityOnCreateFingerprint)
)

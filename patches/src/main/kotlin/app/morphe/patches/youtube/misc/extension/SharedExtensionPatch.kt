package app.morphe.patches.youtube.misc.extension

import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.extension.hooks.applicationInitHook
import app.morphe.patches.youtube.misc.extension.hooks.applicationInitOnCrateHook

val sharedExtensionPatch = sharedExtensionPatch(
    "youtube",
    true,
    applicationInitHook,
    applicationInitOnCrateHook
)

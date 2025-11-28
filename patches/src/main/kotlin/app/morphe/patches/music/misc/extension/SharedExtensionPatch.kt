package app.morphe.patches.music.misc.extension

import app.morphe.patches.music.misc.extension.hooks.applicationInitHook
import app.morphe.patches.music.misc.extension.hooks.applicationInitOnCreateHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    "music",
    applicationInitHook, applicationInitOnCreateHook
)

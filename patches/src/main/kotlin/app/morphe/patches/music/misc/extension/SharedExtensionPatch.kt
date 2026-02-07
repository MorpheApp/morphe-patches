package app.morphe.patches.music.misc.extension

import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitHook
import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitOnCreateHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    "music",
    youTubeMusicApplicationInitHook, youTubeMusicApplicationInitOnCreateHook
)


package app.morphe.patches.music.misc.extension

import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitHook
import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitOnCreateHook
import app.morphe.patches.all.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    listOf("music", "shared-youtube"),
    youTubeMusicApplicationInitHook,
    youTubeMusicApplicationInitOnCreateHook
)


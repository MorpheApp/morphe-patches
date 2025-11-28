package app.morphe.patches.youtube.misc.extension

import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.extension.hooks.*

val sharedExtensionPatch = sharedExtensionPatch("youtube",
    applicationInitHook, applicationInitOnCrateHook)

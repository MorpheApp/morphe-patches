package app.morphe.patches.reddit.utils.extension

import app.morphe.patches.reddit.utils.extension.hooks.applicationInitHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(applicationInitHook)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
package app.morphe.patches.reddit.misc.extension

import app.morphe.patches.reddit.misc.extension.hooks.redditActivityOnCreateHook
import app.morphe.patches.reddit.misc.extension.hooks.redditApplicationOnCreateHook
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    "reddit",
    false,
    redditActivityOnCreateHook,
    redditApplicationOnCreateHook
)

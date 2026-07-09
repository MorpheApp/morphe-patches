/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.extension

import app.morphe.patches.reddit.misc.extension.hooks.redditActivityOnCreateHook
import app.morphe.patches.reddit.misc.extension.hooks.redditApplicationOnCreateHook
import app.morphe.patches.all.misc.extension.sharedExtensionPatch

val sharedExtensionPatch = sharedExtensionPatch(
    listOf("reddit"),
    redditActivityOnCreateHook,
    redditApplicationOnCreateHook
)

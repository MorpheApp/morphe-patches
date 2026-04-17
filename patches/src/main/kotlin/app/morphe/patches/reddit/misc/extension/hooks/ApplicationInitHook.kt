/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.extension.hooks

import app.morphe.patches.all.misc.extension.activityOnCreateExtensionHook

internal val redditActivityOnCreateHook = activityOnCreateExtensionHook(
    activityClassType = "Lcom/reddit/launch/main/MainActivity;"
)

internal val redditApplicationOnCreateHook = activityOnCreateExtensionHook(
    activityClassType = "Lcom/reddit/frontpage/FrontpageApplication;"
)

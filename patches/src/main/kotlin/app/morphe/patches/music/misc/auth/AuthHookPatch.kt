/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4398
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.auth

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.misc.auth.authHookPatch

internal val authHookPatch = authHookPatch(
    emptyPageIdHook = { false },
    block = {
        dependsOn(sharedExtensionPatch)
    }
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patches.shared.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

internal val authHookPatch = authHookPatch(
    emptyPageIdHook = { is_21_02_or_greater },
    block = {
        dependsOn(
            sharedExtensionPatch,
            versionCheckPatch,
        )
    }
)

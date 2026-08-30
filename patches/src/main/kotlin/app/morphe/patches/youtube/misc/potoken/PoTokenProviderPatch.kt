/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.potoken

import app.morphe.patches.shared.misc.potoken.poTokenProviderPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val poTokenProviderPatch = poTokenProviderPatch(
    originalAppPackageName = COMPATIBILITY_YOUTUBE.packageName!!,
    preferenceScreen = PreferenceScreen.MISC,
    block = {
        compatibleWith(COMPATIBILITY_YOUTUBE)

        dependsOn(sharedExtensionPatch)
    }
)

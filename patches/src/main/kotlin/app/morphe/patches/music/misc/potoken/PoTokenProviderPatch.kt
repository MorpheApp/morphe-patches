/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.potoken

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.potoken.poTokenProviderPatch

@Suppress("unused")
val poTokenProviderPatch = poTokenProviderPatch(
    originalAppPackageName = COMPATIBILITY_YOUTUBE_MUSIC.packageName!!,
    preferenceScreen = PreferenceScreen.MISC,
    block = {
        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

        dependsOn(sharedExtensionPatch)
    }
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4135
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

/**
 * Adds the animations of the Morphe Glow 1 and 2 splash screen styles: the Morphe logo comes into
 * focus out of a glow. Each has a dark and a light theme animation.
 */
internal val glowMorpheAnimationResourcePatch = resourcePatch {
    dependsOn(versionCheckPatch)

    execute {
        // The Rive startup animation exists in 21.30+.
        if (is_21_30_or_greater) {
            copyResources(
                "custom-branding",
                ResourceGroup(
                    "raw",
                    "morphe_startup_animation_glow_1_dark.riv",
                    "morphe_startup_animation_glow_1_light.riv",
                    "morphe_startup_animation_glow_1_play_dark.riv",
                    "morphe_startup_animation_glow_1_play_light.riv",
                    "morphe_startup_animation_glow_2_dark.riv",
                    "morphe_startup_animation_glow_2_light.riv",
                    "morphe_startup_animation_glow_2_play_dark.riv",
                    "morphe_startup_animation_glow_2_play_light.riv"
                )
            )
        }
    }
}

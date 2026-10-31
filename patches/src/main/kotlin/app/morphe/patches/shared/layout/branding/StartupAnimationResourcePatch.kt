/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4109
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

/**
 * Adds the Morphe startup animation of each launcher foreground, played by the
 * Morphe splash screen styles in place of the original animation.
 */
val startupAnimationResourcePatch = resourcePatch {
    execute {
        copyResources(
            "custom-branding",
            ResourceGroup(
                "raw",
                "morphe_startup_animation.json",
                "morphe_startup_animation_play.json"
            )
        )
    }
}

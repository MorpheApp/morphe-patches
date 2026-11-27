/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.cast

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal

/**
 * Feature flags gating YouTube's "phone as a companion" Cast overhaul (21.30+):
 * - 45673420: Primary companion mode flag across MdxDirector, MdxDeviceManager,
 *   watch/reel endpoints, and playback routing.
 * - 45823676: Secondary companion flag used alongside 45673420 in 21.45+.
 * - 45780220: Remote control initiated (RCI) playback flag in MdxDirector.
 * - 45761303: Cast companion route selection/logging flag.
 * - 45676577: MDX device discovery companion flag.
 */
internal object CastCompanionFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            literal(45673420L),
            literal(45823676L),
            literal(45780220L),
            literal(45761303L),
            literal(45676577L)
        )
    )
)

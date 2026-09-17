/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3033
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.lyrics

import app.morphe.patcher.Fingerprint

/**
 * Matched only to reach the engagement panel controller class, which owns the field
 * holding the panel currently in the container.
 */
internal object EngagementPanelControllerFingerprint : Fingerprint(
    strings = listOf(
        "EngagementPanelController: cannot show EngagementPanel before EngagementPanelController.init() has been called.",
        "[EngagementPanel] Cannot show EngagementPanel before EngagementPanelController.init() has been called."
    )
)

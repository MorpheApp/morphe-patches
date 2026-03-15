/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.shared

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY_REDDIT: Pair<PackageName, Set<VersionName>> = Pair(
        "com.reddit.frontpage",
        setOf(
            "2026.04.0",
            "2026.03.0",
            "2025.48.0",
        )
    )
}
package app.morphe.patches.reddit.shared

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY_REDDIT: Pair<PackageName, Set<VersionName>> = Pair(
        "com.reddit.frontpage",
        setOf(
            "2026.03.0",
            "2025.43.0",
        )
    )
}
package app.morphe.patches.reddit.shared

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY: Pair<PackageName, Set<VersionName>> = Pair(
        "com.reddit.frontpage",
        setOf(
            "2026.03.0",
            "2026.02.0",
            "2026.01.0",
            "2025.52.0",
            "2025.45.0",
            "2025.43.0",
            "2025.40.0",
        )
    )
}
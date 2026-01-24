package app.morphe.patches.reddit.utils.compatibility

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    internal const val REDDIT_PACKAGE_NAME = "com.reddit.frontpage"

    val COMPATIBLE_PACKAGE: Pair<PackageName, Set<VersionName>?> = Pair(
        REDDIT_PACKAGE_NAME,
        setOf(
            "2025.40.0",
            "2025.43.0",
            "2025.45.0",
            "2025.52.0",
            "2026.01.0",
            "2026.02.0",
            "2026.03.0",
            "2026.04.0"
        )
    )
}
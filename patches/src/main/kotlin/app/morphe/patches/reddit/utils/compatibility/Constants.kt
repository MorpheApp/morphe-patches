package app.morphe.patches.reddit.utils.compatibility

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY_YOUTUBE: Pair<PackageName, Set<VersionName>> = Pair(
        "com.google.android.youtube",
        setOf(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    val COMPATIBILITY_YOUTUBE_MUSIC: Pair<PackageName, Set<VersionName>> = Pair(
        "com.google.android.apps.youtube.music",
        setOf(
            "7.29.52",
            "8.10.52",
            "8.37.56",
            "8.40.54",
        )
    )

    val COMPATIBILITY_REDDIT: Pair<PackageName, Set<VersionName>> = Pair(
        "com.reddit.frontpage",
        setOf(
            "2025.40.0",
            "2025.43.0",
            "2025.45.0",
            "2025.52.0",
            "2026.01.0",
            "2026.02.0",
            "2026.03.0",
        )
    )
}
package app.morphe.patches.youtube.shared

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY_YOUTUBE: Pair<PackageName, Set<VersionName>> = Pair(
        "com.google.android.youtube",
        setOf(
            "20.40.45",
            "20.37.48",
            "20.31.42",
            "20.26.46",
            "20.21.37",
            "20.14.43",
        )
    )
}
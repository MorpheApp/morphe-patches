package app.morphe.patches.youtube.shared

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY_YOUTUBE: Pair<PackageName, Set<VersionName>> = Pair(
        "com.google.android.youtube",
        setOf(
            "21.12.522",
            "20.45.36",
            "20.44.38",
            "20.40.45",
            "20.31.42",
            "20.21.37",
        )
    )
}
package app.morphe.patches.music.shared

import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName

internal object Constants {
    val COMPATIBILITY_YOUTUBE_MUSIC: Pair<PackageName, Set<VersionName>> = Pair(
        "com.google.android.apps.youtube.music",
        setOf(
            "8.40.54",
            "8.37.56",
            "8.10.52",
            "7.29.52",
        )
    )
}
package app.morphe.patches.music.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    // TODO: Rename to COMPATIBILITY_YT_MUSIC
    val COMPATIBILITY_YOUTUBE_MUSIC = Compatibility(
        name = "YT Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0000,
        targets = listOf(
            AppTarget(
                version = "9.10.52",
                minSdk = 26,
                isExperimental = true,
            ),
            AppTarget(
                version = "8.50.51",
                minSdk = 26,
                isExperimental = true,
            ),
            AppTarget(
                version = "8.44.54",
                minSdk = 26,
            ),
            AppTarget(
                version = "8.40.54",
                minSdk = 26,
                isExperimental = true
            ),
            AppTarget(
                version = "8.10.52",
                minSdk = 26,
                isExperimental = true
            ),
            AppTarget(
                version = "7.29.52",
                minSdk = 26
            )
        )
    )
}

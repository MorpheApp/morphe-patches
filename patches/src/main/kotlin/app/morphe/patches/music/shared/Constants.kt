package app.morphe.patches.music.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_YOUTUBE_MUSIC = Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0000,
        signatures = setOf(
            // Android 13+
            "6a2f65ec694a6a632acdcb5080912a565f903d4b8d83f0eb8e44fbdf2660d8e1",
            // Android 7+
            "a2a1ad7ba7f41dfca4514e2afeb90691719af6d0fdbed4b09bbf0ed897701ceb"
        ),
        targets = listOf(
            AppTarget(
                version = "9.24.51",
                minSdk = 26,
                isExperimental = true,
            ),
            AppTarget(
                version = "9.23.52",
                minSdk = 26,
                isExperimental = true,
            ),
            AppTarget(
                version = "9.22.53",
                minSdk = 26,
                isExperimental = true,
            ),
            AppTarget(
                version = "9.15.51",
                minSdk = 26
            ),
            AppTarget(
                version = "8.51.51",
                minSdk = 26,
            ),
            AppTarget(
                version = "7.29.52",
                minSdk = 26
            )
        )
    )
}

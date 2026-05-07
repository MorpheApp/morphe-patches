package app.morphe.patches.youtube.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_YOUTUBE = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0033,
        signatures = setOf(
            // Android 13+
            "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
            // Android 7+
            "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"
        ),
        targets = listOf(
            AppTarget(
                version = "21.18.163",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "21.17.480",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "21.16.256",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "21.05.265",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "20.47.62",
                minSdk = 28
            ),
            AppTarget(
                version = "20.31.42",
                minSdk = 28
            ),
            AppTarget(
                version = "20.21.37",
                minSdk = 26
            )
        )
    )
}

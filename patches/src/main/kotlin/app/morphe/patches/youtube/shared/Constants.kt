package app.morphe.patches.youtube.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_YOUTUBE = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0033,
        targets = listOf(
            AppTarget(
                version = "21.11.480",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "21.05.265",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "20.50.40",
                minSdk = 28,
                isExperimental = true
            ),
            AppTarget(
                version = "20.44.38",
                minSdk = 28
            ),
            AppTarget(
                version = "20.40.45",
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

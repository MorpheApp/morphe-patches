/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
package app.morphe.patches.reddit.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_REDDIT = Compatibility(
        name = "Reddit",
        packageName = "com.reddit.frontpage",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xFF4500,
        targets = listOf(
            AppTarget(
                version = "2026.10.0",
                minSdk = 28,
                isExperimental = true,
            ),
            AppTarget(
                version = "2026.04.0",
                minSdk = 28,
            ),
            AppTarget(
                version = "2026.03.0",
                minSdk = 28,
            ),
            AppTarget(
                version = "2025.48.0",
                minSdk = 28,
            )
        )
    )
}

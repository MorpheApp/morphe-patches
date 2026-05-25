/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.ifunny.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_IFUNNY = Compatibility(
        name = "iFunny",
        packageName = "mobi.ifunny",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x000000,
        signatures = setOf(
            "8e16f4751388194da1f7f5feafea5e86ad0424cc394e61f78b87dd328fdebebd"
        ),
        targets = listOf(
            AppTarget(
                version = "10.39.11",
                minSdk = 26,
            )
        )
    )
}

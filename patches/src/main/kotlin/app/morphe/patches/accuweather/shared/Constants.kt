package app.morphe.patches.accuweather.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_ACCUWEATHER = Compatibility(
        name = "AccuWeather",
        packageName = "com.accuweather.android",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xE8710A,
        signatures = setOf(
            "46120af63fcc75ca5844d181a87c66e284cb0e1d6b7f7fa56d3254720ba68e02"
        ),
        targets = listOf(
            AppTarget(
                version = "21.1.15-3-rc",
                minSdk = 26
            )
        )
    )
}

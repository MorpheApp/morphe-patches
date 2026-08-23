package app.morphe.patches.meteomedia.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    /**
     * MétéoMédia / The Weather Network share a single Android app
     * (`com.pelmorex.WeatherEyeAndroid`); the French Canadian branding is
     * MétéoMédia. The signature is the Google Play app-signing certificate,
     * so it matches APKs pulled from Google Play (e.g. as a split bundle).
     */
    val COMPATIBILITY_METEOMEDIA = Compatibility(
        name = "MeteoMedia",
        packageName = "com.pelmorex.WeatherEyeAndroid",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xF0E040,
        signatures = setOf(
            "219159f2d65561a2398f251cba75a17eedeca0d4dbcfd304261c9231a3b15a5c"
        ),
        targets = listOf(
            AppTarget(
                version = "7.18.1.11650",
                minSdk = 24
            )
        )
    )
}

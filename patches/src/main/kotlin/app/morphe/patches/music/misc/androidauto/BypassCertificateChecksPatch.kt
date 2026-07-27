package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.returnEarly

@Suppress("unused")
val bypassCertificateChecksPatch = bytecodePatch(
    name = "Bypass certificate checks",
    description = "Bypasses certificate checks which prevent YouTube Music from working on Android Auto.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        CheckCertificateFingerprint.method.returnEarly(true)

        // Devices with real Google Play services installed alongside microG crash inside
        // the Dynamite-based Google signature verifier (IllegalStateException:
        // "Missing DynamiteApplicationContext") before the fingerprint check above is
        // even evaluated.  Report "not Google-signed" immediately, without touching
        // Dynamite; authorization still succeeds via the fingerprint patch above.
        IsGoogleSignedFingerprint.method.returnEarly(false)
    }
}

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY
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

    compatibleWith(COMPATIBILITY)

    execute {
        CheckCertificateFingerprint.method.returnEarly(true)
    }
}

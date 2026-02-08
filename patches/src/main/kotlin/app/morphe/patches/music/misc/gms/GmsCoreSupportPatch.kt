package app.morphe.patches.music.misc.gms

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.fileprovider.fileProviderPatch
import app.morphe.patches.music.misc.gms.Constants.MORPHE_MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.CastContextFetchFingerprint
import app.morphe.patches.shared.PrimeMethodFingerprint
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = MUSIC_PACKAGE_NAME,
    toPackageName = MORPHE_MUSIC_PACKAGE_NAME,
    primeMethodFingerprint = PrimeMethodFingerprint,
    earlyReturnFingerprints = setOf(
        CastContextFetchFingerprint,
    ),
    mainActivityOnCreateFingerprint = MusicActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
) {
    dependsOn(spoofVideoStreamsPatch)

    compatibleWith(COMPATIBILITY)
}

private fun gmsCoreSupportResourcePatch() =
    app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch(
        fromPackageName = MUSIC_PACKAGE_NAME,
        toPackageName = MORPHE_MUSIC_PACKAGE_NAME,
        spoofedPackageSignature = "afb0fed5eeaebdd86f56a97742f4b6b33ef59875",
        screen = PreferenceScreen.MISC,
        block = {
            dependsOn(
                settingsPatch,
                fileProviderPatch(
                    MUSIC_PACKAGE_NAME,
                    MORPHE_MUSIC_PACKAGE_NAME
                )
            )
        }
    )

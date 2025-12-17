package app.morphe.patches.youtube.misc.gms

import app.morphe.patcher.patch.Option
import app.morphe.patches.shared.CastContextFetchFingerprint
import app.morphe.patches.shared.PrimeMethodFingerprint
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.youtube.layout.buttons.overlay.hidePlayerOverlayButtonsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.gms.Constants.MORPHE_YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.gms.Constants.YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.youtube.shared.MainActivityOnCreateFingerprint

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = YOUTUBE_PACKAGE_NAME,
    toPackageName = MORPHE_YOUTUBE_PACKAGE_NAME,
    primeMethodFingerprint = PrimeMethodFingerprint,
    earlyReturnFingerprints = setOf(
        CastContextFetchFingerprint,
    ),
    mainActivityOnCreateFingerprint = MainActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
) {
    dependsOn(
        hidePlayerOverlayButtonsPatch, // Hide non-functional cast button.
        spoofVideoStreamsPatch,
    )

    compatibleWith(
        YOUTUBE_PACKAGE_NAME(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )
}

private fun gmsCoreSupportResourcePatch(
    gmsCoreVendorGroupIdOption: Option<String>,
) = app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch(
    fromPackageName = YOUTUBE_PACKAGE_NAME,
    toPackageName = MORPHE_YOUTUBE_PACKAGE_NAME,
    gmsCoreVendorGroupIdOption = gmsCoreVendorGroupIdOption,
    spoofedPackageSignature = "24bb24c05e47e0aefa68a58a766179d9b613a600",
    executeBlock = {

        val gmsCoreVendorGroupId by gmsCoreVendorGroupIdOption

        PreferenceScreen.MISC.addPreferences(
            IntentPreference(
                "microg_settings",
                intent = IntentPreference.Intent("", "org.microg.gms.ui.SettingsActivity") {
                    "$gmsCoreVendorGroupId.android.gms"
                }
            )
        )
    }
) {
    dependsOn(
        settingsPatch,
        accountCredentialsInvalidTextPatch
    )
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patches.shared.PrimeMethodFingerprint
import app.morphe.patches.shared.misc.gms.EXTENSION_CLASS
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch
import app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.gms.Constants.MORPHE_YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.gms.Constants.YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.playservice.is_21_45_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = YOUTUBE_PACKAGE_NAME,
    toPackageNameDefault = MORPHE_YOUTUBE_PACKAGE_NAME,
    primeMethodFingerprint = PrimeMethodFingerprint,
    mainActivityOnCreateFingerprint = YouTubeActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
    useLegacyGnpFingerprint = { !is_21_45_or_greater },
    executeBlock = {
        // GmsCore allows the capabilities it can not look up, which makes every account a supervised account.
        AccountHasCapabilitiesFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->denySupervisedAccountCapability([Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :ask_gms_core
                # Same result as Google Play services for accounts without the capability.
                const/4 v0, 0x2
                invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v0
                return-object v0
                :ask_gms_core
                nop
            """
        )
    }
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
        spoofVideoStreamsPatch,
        deviceComplianceCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)
}

private fun gmsCoreSupportResourcePatch() = gmsCoreSupportResourcePatch(
    fromPackageName = YOUTUBE_PACKAGE_NAME,
    toPackageNameDefault = MORPHE_YOUTUBE_PACKAGE_NAME,
    spoofedPackageSignature = "24bb24c05e47e0aefa68a58a766179d9b613a600",
    screen = PreferenceScreen.MISC,
    block = {
        dependsOn(
            settingsPatch,
            accountCredentialsInvalidTextPatch
        )
    }
)

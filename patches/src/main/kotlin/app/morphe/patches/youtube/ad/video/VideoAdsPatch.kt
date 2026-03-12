package app.morphe.patches.youtube.ad.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addOSNameHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/VideoAdsPatch;"

val videoAdsPatch = bytecodePatch(
    name = "Video ads",
    description = "Adds an option to remove ads in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        clientContextHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_hide_video_ads"),
        )

        setOf(
            LoadVideoAdsFingerprint,
            PlayerBytesAdLayoutFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideVideoAds()Z
                    move-result v0
                    if-eqz v0, :show_video_ads
                    return-void
                    :show_video_ads
                    nop
                """
            )
        }

        setOf(
            Endpoint.GET_WATCH,
            Endpoint.PLAYER,
            Endpoint.REEL,
        ).forEach { endpoint ->
            addOSNameHook(
                endpoint,
                "$EXTENSION_CLASS_DESCRIPTOR->hideVideoAds(Ljava/lang/String;)Ljava/lang/String;",
            )
        }
    }
}

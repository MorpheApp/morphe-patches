package app.morphe.patches.youtube.ad.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addOSNameHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

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

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_hide_video_ads"),
        )

        LoadVideoAdsFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->shouldShowAds()Z
                move-result v0
                if-nez v0, :show_video_ads
                return-void
            """,
            ExternalLabel("show_video_ads", LoadVideoAdsFingerprint.method.getInstruction(0)),
        )

        addOSNameHook(
            Endpoint.REEL,
            "$EXTENSION_CLASS_DESCRIPTOR->hideShortsAds(Ljava/lang/String;)Ljava/lang/String;",
        )
    }
}

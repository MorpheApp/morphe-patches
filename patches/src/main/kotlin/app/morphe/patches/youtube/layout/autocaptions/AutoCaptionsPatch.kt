package app.morphe.patches.youtube.layout.autocaptions

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/DisableAutoCaptionsPatch;"

val autoCaptionsPatch = bytecodePatch(
    name = "Disable auto captions",
    description = "Adds an option to disable captions from being automatically enabled.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.autocaptions.autoCaptionsPatch")

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_auto_captions"),
        )

        subtitleTrackFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->disableAutoCaptions()Z
                move-result v0
                if-eqz v0, :auto_captions_enabled
                const/4 v0, 0x1
                return v0
                :auto_captions_enabled
                nop
            """
        )

        arrayOf(
            startVideoInformerFingerprint to 0,
            storyboardRendererDecoderRecommendedLevelFingerprint to 1
        ).forEach { (fingerprint, enabled) ->
            fingerprint.method.addInstructions(
                0,
                """
                    const/4 v0, 0x$enabled
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->setCaptionsButtonStatus(Z)V
                """
            )
        }
    }
}

package app.morphe.patches.youtube.layout.autocaptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_26_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.SubtitleButtonControllerFingerprint

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/AutoCaptionsPatch;"

@Suppress("unused")
val autoCaptionsPatch = bytecodePatch(
    name = "Auto captions",
    description = "Adds an option to disable captions from being automatically enabled.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
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
        PreferenceScreen.PLAYER.addPreferences(
            if (is_20_26_or_greater) {
                ListPreference("morphe_auto_captions_style")
            } else {
                ListPreference(
                    key = "morphe_auto_captions_style",
                    entriesKey = "morphe_auto_captions_style_legacy_entries",
                    entryValuesKey = "morphe_auto_captions_style_legacy_entry_values"
                )
            }
        )

        SubtitleTrackFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->disableAutoCaptions()Z
                move-result v0
                if-nez v0, :auto_captions_enabled
                const/4 v0, 0x1
                return v0
                :auto_captions_enabled
                nop
            """
        )

        arrayOf(
            StartVideoInformerFingerprint to 0,
            SubtitleButtonControllerFingerprint to 1
        ).forEach { (fingerprint, enabled) ->
            fingerprint.method.addInstructions(
                0,
                """
                    const/4 v0, 0x$enabled
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->setCaptionsButtonStatus(Z)V
                """
            )
        }

        if (is_20_26_or_greater) {
            NoVolumeCaptionsFeatureFlagFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->disableMuteAutoCaptions()Z
                        move-result v0
                        return v0
                        nop
                    """
                )
            }
        }
    }
}

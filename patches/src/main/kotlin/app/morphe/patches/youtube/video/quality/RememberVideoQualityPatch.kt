package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.VideoQualityChangedFingerprint
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/playback/quality/RememberVideoQualityPatch;"

val rememberVideoQualityPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        settingsMenuVideoQualityGroup.addAll(listOf(
            ListPreference(
                key = "morphe_video_quality_default_mobile",
                entriesKey = "morphe_video_quality_default_entries",
                entryValuesKey = "morphe_video_quality_default_entry_values"
            ),
            ListPreference(
                key = "morphe_video_quality_default_wifi",
                entriesKey = "morphe_video_quality_default_entries",
                entryValuesKey = "morphe_video_quality_default_entry_values"
            ),
            SwitchPreference("morphe_remember_video_quality_last_selected"),

            ListPreference(
                key = "morphe_shorts_quality_default_mobile",
                entriesKey = "morphe_shorts_quality_default_entries",
                entryValuesKey = "morphe_shorts_quality_default_entry_values",
            ),
            ListPreference(
                key = "morphe_shorts_quality_default_wifi",
                entriesKey = "morphe_shorts_quality_default_entries",
                entryValuesKey = "morphe_shorts_quality_default_entry_values"
            ),
            SwitchPreference("morphe_remember_shorts_quality_last_selected"),
            SwitchPreference("morphe_remember_video_quality_last_selected_toast")
        ))

        onCreateHook(EXTENSION_CLASS_DESCRIPTOR, "newVideoStarted")

        // Inject a call to remember the selected quality for Shorts.
        VideoQualityItemOnClickFingerprint.match(
            VideoQualityItemOnClickParentFingerprint.classDef
        ).method.addInstruction(
            0,
            "invoke-static { p3 }, $EXTENSION_CLASS_DESCRIPTOR->userChangedShortsQuality(I)V"
        )

        // Inject a call to remember the user selected quality for regular videos.
        VideoQualityChangedFingerprint.method.apply {
            val index = VideoQualityChangedFingerprint.instructionMatches[3].index
            val register = getInstruction<TwoRegisterInstruction>(index).registerA

            addInstruction(
                index + 1,
                "invoke-static { v$register }, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->userChangedQuality(I)V",
            )
        }
    }
}

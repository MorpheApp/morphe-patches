package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.seekbar.seekbarColorPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.SeekbarFingerprint
import app.morphe.patches.youtube.shared.SeekbarOnDrawFingerprint
import app.morphe.util.insertLiteralOverride

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/HideSeekbarPatch;"

val hideSeekbarPatch = bytecodePatch(
    description = "Adds an option to hide the seekbar.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        seekbarColorPatch,
        versionCheckPatch
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_hide_seekbar"),
            SwitchPreference("morphe_hide_seekbar_thumbnail"),
            SwitchPreference("morphe_fullscreen_large_seekbar"),
        )

        SeekbarOnDrawFingerprint.match(SeekbarFingerprint.originalClassDef).method.addInstructionsWithLabels(
            0,
            """
                const/4 v0, 0x0
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideSeekbar()Z
                move-result v0
                if-eqz v0, :hide_seekbar
                return-void
                :hide_seekbar
                nop
            """
        )

        if (is_20_28_or_greater) {
            FullscreenLargeSeekbarFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->useFullscreenLargeSeekbar(Z)Z"
                )
            }
        }
    }
}

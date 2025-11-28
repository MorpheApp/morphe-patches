package app.morphe.patches.youtube.interaction.seekbar

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.seekbar.fullscreenSeekbarThumbnailsFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_17_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_09_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import java.util.logging.Logger

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/SeekbarThumbnailsPatch;"

val seekbarThumbnailsPatch = bytecodePatch(
    description = "Adds an option to use high quality fullscreen seekbar thumbnails."
) {
    dependsOn(
        sharedExtensionPatch,
        addResourcesPatch,
        versionCheckPatch,
    )

    execute {
        if (is_20_09_or_greater) {
            // High quality seekbar thumbnails is partially broken in 20.09
            // and the code is completely removed in 20.10+
            return@execute
        }

        addResources("youtube", "layout.seekbar.seekbarThumbnailsPatch")

        if (is_19_17_or_greater) {
            PreferenceScreen.SEEKBAR.addPreferences(
                SwitchPreference("revanced_seekbar_thumbnails_high_quality")
            )
        } else {
            PreferenceScreen.SEEKBAR.addPreferences(
                SwitchPreference("revanced_restore_old_seekbar_thumbnails"),
                SwitchPreference(
                    key = "revanced_seekbar_thumbnails_high_quality",
                    summaryOnKey = "revanced_seekbar_thumbnails_high_quality_legacy_summary_on",
                    summaryOffKey = "revanced_seekbar_thumbnails_high_quality_legacy_summary_on"
                )
            )

            fullscreenSeekbarThumbnailsFingerprint.method.apply {
                val moveResultIndex = instructions.lastIndex - 1

                addInstruction(
                    moveResultIndex,
                    "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->useFullscreenSeekbarThumbnails()Z",
                )
            }
        }

        fullscreenSeekbarThumbnailsQualityFingerprint.method.addInstructions(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->useHighQualityFullscreenThumbnails()Z
                move-result v0
                return v0
            """
        )
    }
}

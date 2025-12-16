package app.morphe.patches.youtube.layout.hide.relatedvideooverlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideRelatedVideoOverlayPatch;"

@Suppress("unused")
val hideRelatedVideoOverlayPatch = bytecodePatch(
    name = "Hide related video overlay",
    description = "Adds an option to hide the related video overlay shown when swiping up in fullscreen.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        resourceMappingPatch,
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
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_related_videos_overlay")
        )

        RelatedEndScreenResultsFingerprint.match(
            RelatedEndScreenResultsParentFingerprint.originalClassDef
        ).method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideRelatedVideoOverlay()Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                """,
                ExternalLabel("show", getInstruction(0))
            )
        }
    }
}

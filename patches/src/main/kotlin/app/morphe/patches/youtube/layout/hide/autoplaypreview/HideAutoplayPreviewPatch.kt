package app.morphe.patches.youtube.layout.hide.autoplaypreview

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
    "Lapp/morphe/extension/youtube/patches/HideAutoplayPreviewPatch;"

@Suppress("unused")
val hideAutoplayPreviewPatch = bytecodePatch(
    name = "Hide autoplay preview",
    description = "Adds an option to hide the autoplay preview at the end of videos.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        resourceMappingPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_autoplay_preview")
        )

        AutoplayPreviewFingerprint.match(
            AutoNavPreviewFingerprint.originalClassDef
        ).method.apply {
             addInstructionsWithLabels(
                 0,
                 """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideAutoplayPreview()Z
                    move-result v0
                    if-nez v0, :hidden
                    return-void
                """,
                ExternalLabel("hidden", getInstruction(0))
            )
        }
    }
}

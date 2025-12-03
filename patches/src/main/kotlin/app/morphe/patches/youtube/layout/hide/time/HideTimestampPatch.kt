package app.morphe.patches.youtube.layout.hide.time

import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/HideTimestampPatch;"

val hideTimestampPatch = bytecodePatch(
    name = "Hide timestamp",
    description = "Adds an option to hide the timestamp in the bottom left of the video player.",
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
        addResources("youtube", "layout.hide.time.hideTimestampPatch")

        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_hide_timestamp"),
        )

        timeCounterFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideTimestamp()Z
                move-result v0
                if-eqz v0, :hide_time
                return-void
                :hide_time
                nop
            """
        )
    }
}

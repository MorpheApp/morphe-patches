package app.morphe.patches.youtube.misc.hapticfeedback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/DisableHapticFeedbackPatch;"

@Suppress("unused")
val disableHapticFeedbackPatch = bytecodePatch(
    name = "Disable haptic feedback",
    description = "Adds an option to disable haptic feedback in the player for various actions.",
) {
    dependsOn(
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                "morphe_disable_haptic_feedback",
                preferences = setOf(
                    SwitchPreference("morphe_disable_haptic_feedback_chapters"),
                    SwitchPreference("morphe_disable_haptic_feedback_precise_seeking"),
                    SwitchPreference("morphe_disable_haptic_feedback_seek_undo"),
                    SwitchPreference("morphe_disable_haptic_feedback_zoom"),
                )
            )
        )

        arrayOf(
            MarkerHapticsFingerprint to "disableChapterVibrate",
            ScrubbingHapticsFingerprint to "disablePreciseSeekingVibrate",
            SeekUndoHapticsFingerprint to "disableSeekUndoVibrate",
            ZoomHapticsFingerprint to "disableZoomVibrate"
        ).forEach { (fingerprint, methodName) ->
            fingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->$methodName()Z
                        move-result v0
                        if-eqz v0, :vibrate
                        return-void
                    """,
                    ExternalLabel("vibrate", getInstruction(0))
                )
            }
        }
    }
}

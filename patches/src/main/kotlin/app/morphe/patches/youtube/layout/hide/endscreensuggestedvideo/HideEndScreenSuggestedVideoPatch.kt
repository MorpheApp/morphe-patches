package app.morphe.patches.youtube.layout.hide.endscreensuggestedvideo

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideEndScreenSuggestedVideoPatch;"

@Suppress("unused")
val hideEndScreenSuggestedVideoPatch = bytecodePatch(
    name = "Hide end screen suggested video",
    description = "Adds an option to hide the suggested video at the end of videos.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_end_screen_suggested_video"),
        )

        val endScreenMethod = RemoveOnLayoutChangeListenerFingerprint.instructionMatches[1]
            .instruction.getReference<MethodReference>()!!.getMutableMethod()

        endScreenMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideEndScreenSuggestedVideo()Z
                move-result v0
                if-eqz v0, :ignore
                return-void
                :ignore
                nop
            """
        )
    }
}

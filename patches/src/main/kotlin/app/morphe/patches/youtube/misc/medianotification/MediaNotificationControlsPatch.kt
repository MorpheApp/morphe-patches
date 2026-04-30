package app.morphe.patches.youtube.misc.medianotification

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/MediaNotificationControlsPatch;"

@Suppress("unused")
val mediaNotificationControlsPatch = bytecodePatch(
    name = "Media notification controls",
    description = "Adds options to disable the seekbar and previous/next buttons in the " +
            "media notification and headphone controls.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerOverlayButtonsSettingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            PreferenceCategory(
                titleKey = null,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_hide_notification_media_prev_next"),
                    SwitchPreference("morphe_hide_notification_media_seekbar"),
                )
            )
        )

        MediaSessionSetPlaybackStateFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<FiveRegisterInstruction>(index).registerD

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->changePlaybackState(Landroid/media/session/PlaybackState;)Landroid/media/session/PlaybackState;
                        move-result-object v$register
                    """
                )
            }
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.delay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.addPlayerFlyoutMenuPreferences
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.playerFlyoutPreferences
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/VideoDelayPatch;"

private const val RECEIVER_CLASS_NAME =
    "app.morphe.extension.youtube.patches.VideoDelayReceiver"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/VideoDelayDialogButton;"

private val videoDelayResourcePatch = patch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        addLegacyBottomControl("videodelaybutton")

        copyResources(
            "videodelaybutton",
            ResourceGroup("drawable", "morphe_ic_video_delay.xml")
        )

        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().appendChild("receiver") {
                setAttribute("android:name", RECEIVER_CLASS_NAME)
                setAttribute("android:exported", "true")
                appendChild("intent-filter") {
                    appendChild("action") {
                        setAttribute("android:name", "app.morphe.extension.youtube.SET_VIDEO_DELAY")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val videoDelayPatch = patch(
    name = "Video delay",
    description = "Adds an option to delay the video, to keep it in sync with laggy Bluetooth audio."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        playerFlyoutPreferences,
        flyoutPatch,
        videoDelayResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            NonInteractivePreference(
                key = "morphe_video_delay",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            SwitchPreference("morphe_video_delay_bluetooth_only", summary = true)
        )

        addPlayerOverlayPreferences(
            SwitchPreference("morphe_video_delay_button_overlay", summary = true)
        )
        addPlayerFlyoutMenuPreferences(
            SwitchPreference("morphe_video_delay_button_flyout")
        )

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)

        // Video frames are released when the audio clock reaches their timestamp,
        // so reporting an earlier audio position holds every frame back by the same amount.
        // Both audio renderers are hooked, as the player uses either depending on the audio codec.
        listOf(
            MediaCodecAudioRendererPositionFingerprint,
            DecoderAudioRendererPositionFingerprint
        ).forEach {
            it.method.apply {
                val returnIndex = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                addInstructions(
                    returnIndex,
                    """
                        invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS_DESCRIPTOR->delayPositionUs(J)J
                        move-result-wide v$register
                    """
                )
            }
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2616
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.format.hookAdaptiveFormat
import app.morphe.patches.youtube.video.format.videoFormatPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS_VIDEO_SCALE =
    "Lapp/morphe/extension/youtube/patches/FullscreenVideoScalePatch;"
private const val EXTENSION_PLAYER_VIEW_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/FullscreenVideoScalePatch$PlayerViewInterface;"
private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/FullscreenVideoScaleButton;"

private val fullscreenVideoScaleResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
    )

    execute {
        copyPlayerButtonIcons(
            "fullscreenvideoscalebutton",
            "morphe_fullscreen_video_scale_fit",
            "morphe_fullscreen_video_scale_stretch",
            "morphe_fullscreen_video_scale_zoom"
        )

        addLegacyBottomControl("fullscreenvideoscalebutton")
    }
}

@Suppress("unused")
val fullscreenVideoScalePatch = bytecodePatch(
    name = "Fullscreen video scale",
    description = "Adds options to stretch or zoom videos to fill the screen in fullscreen mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        videoFormatPatch,
        fullscreenVideoScaleResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            ListPreference("morphe_fullscreen_video_scale")
        )

        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_fullscreen_video_scale_button", summary = true),
                SwitchPreference("morphe_fullscreen_video_scale_button_fullscreen_only", summary = true)
            )
        )

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)
        hookAdaptiveFormat("$EXTENSION_CLASS_VIDEO_SCALE->setVideoAspectRatio")

        YouTubePlayerOverlaysLayoutConstructorFingerprint.matchAll().forEach {
            it.method.addInstruction(
                it.instructionMatches.first().index,
                "invoke-static { p0 }, $EXTENSION_CLASS_VIDEO_SCALE->" +
                        "attachPlayerOverlay(Landroid/view/View;)V"
            )
        }

        PlayerViewConstructorFingerprint.let {
            val videoViewField = it.instructionMatches.last().getFieldAccessed()

            it.classDef.apply {
                interfaces.add(EXTENSION_PLAYER_VIEW_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getVideoView",
                        listOf(),
                        "Landroid/view/View;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $videoViewField
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        YouTubePlayerViewOnLayoutFingerprint.let {
            it.method.addInstructionsAtControlFlowLabel(
                it.instructionMatches.first().index,
                "invoke-static { p0 }, $EXTENSION_CLASS_VIDEO_SCALE->" +
                        "onPlayerViewLayout(Landroid/view/View;)V"
            )
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.PlayerTypeEnumFingerprint
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsToEnd
import app.morphe.util.copyResources
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val STRETCH_VIDEO_EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/StretchVideoPatch;"
private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/StretchVideoButton;"

private val stretchVideoResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
    )

    execute {
        copyResources(
            "stretchvideobutton",
            ResourceGroup(
                "drawable",
                "morphe_stretch_video_fit.xml",
                "morphe_stretch_video_fit_bold.xml",
                "morphe_stretch_video_stretch.xml",
                "morphe_stretch_video_stretch_bold.xml",
                "morphe_stretch_video_zoom.xml",
                "morphe_stretch_video_zoom_bold.xml"
            )
        )

        addLegacyBottomControl("stretchvideobutton")
    }
}

@Suppress("unused")
val stretchVideoPatch = bytecodePatch(
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
        stretchVideoResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_fullscreen_video_scale")
        )

        addPlayerOverlayPreferences(
            SwitchPreference("morphe_stretch_video_button", summary = true)
        )

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)

        Fingerprint(
            definingClass = "/YouTubePlayerOverlaysLayout;",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(PlayerTypeEnumFingerprint.originalClassDef.type)
        ).method.addInstruction(
            0,
            "invoke-static { p0 }, $STRETCH_VIDEO_EXTENSION_CLASS->" +
                    "attachPlayerOverlay(Landroid/view/View;)V"
        )

        YouTubePlayerOverlaysLayoutConstructorFingerprint.classDefOrNull?.methods?.forEach { method ->
            if (!MethodUtil.isConstructor(method) || method.implementation == null) return@forEach
            method.addInstruction(
                method.implementation!!.instructions.lastIndex,
                "invoke-static { p0 }, $STRETCH_VIDEO_EXTENSION_CLASS->" +
                        "attachPlayerOverlay(Landroid/view/View;)V"
            )
        }

        YouTubePlayerViewOnLayoutFingerprint.method.addInstructionsToEnd(
            "invoke-static { p0 }, $STRETCH_VIDEO_EXTENSION_CLASS->" +
                    "onPlayerViewLayout(Landroid/view/View;)V"
        )

        SixteenNinePlayerViewMeasureFingerprint.classDefOrNull?.methods?.forEach { method ->
            if (method.name != "onLayout" || method.parameterTypes.size != 5) return@forEach
            if (method.implementation == null) return@forEach
            method.addInstructionsToEnd(
                "invoke-static { p0 }, $STRETCH_VIDEO_EXTENSION_CLASS->" +
                        "onPlayerViewLayout(Landroid/view/View;)V"
            )
        }
    }
}

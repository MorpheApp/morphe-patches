/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.interaction.reload.reloadVideoButtonPatch
import app.morphe.patches.youtube.layout.player.fullscreen.openVideosFullscreenHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_20_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenShortsInRegularPlayerPatch;"

@Suppress("unused")
val openShortsInRegularPlayerPatch = bytecodePatch(
    name = "Open Shorts in regular player",
    description = "Adds options to open Shorts in the regular video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        openVideosFullscreenHookPatch,
        reloadVideoButtonPatch,
        navigationBarHookPatch,
        versionCheckPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            ListPreference("morphe_shorts_player_type")
        )

        // Activity is used as the context to launch an Intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        val playbackStartVideoIdMethodName = PlaybackStartDescriptorToStringFingerprint
            .instructionMatches[1].getMethodCalled().name

        // Same method is modified by openChannelOfLiveAvatarPatch,
        // and by coincidence that patch runs after this patch which is critical
        // because that patch behavior is prioritized over this patch.
        (if (is_21_20_or_greater) ShortsPlaybackIntentFingerprint
        else ShortsPlaybackIntentFingerprintLegacy).method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                
                invoke-virtual { v0 }, ${PlaybackStartDescriptorToStringFingerprint.classDef}->$playbackStartVideoIdMethodName()Ljava/lang/String;
                move-result-object v1
                invoke-static { v1 }, $EXTENSION_CLASS->openShort(Ljava/lang/String;)Z
                move-result v1
                if-eqz v1, :disabled
                return-void
                
                :disabled
                nop
            """
        )
    }
}

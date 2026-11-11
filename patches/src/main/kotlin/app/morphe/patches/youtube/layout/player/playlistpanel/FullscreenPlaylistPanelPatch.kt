/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4664
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.playlistpanel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FullscreenPlaylistPanelPatch;"

@Suppress("unused")
val fullscreenPlaylistPanelPatch = bytecodePatch(
    name = "Fullscreen playlist panel",
    description = "Adds an option to show the playlist panel over the fullscreen player on tablets, " +
        "instead of exiting fullscreen.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // The id of the panel that opens.
        engagementPanelHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_fullscreen_playlist_panel")
        )

        // Keep fullscreen when the playlist panel opens.
        PanelExitsFullscreenFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->keepFullscreen()Z
                move-result v0
                if-eqz v0, :exit_rule
                const/4 v0, 0x0
                return v0
                :exit_rule
                nop
            """
        )

        // Use the panel overlay over the fullscreen player, which YouTube enables by a server
        // config only on phones. Without it the panel opens in the watch column, which fullscreen hides.
        val panelModeFingerprint = getPanelModeFingerprint(PanelModeEnumFingerprint.originalClassDef.type)
        panelModeFingerprint.matchAll().forEach { match ->
            match.method.apply {
                // The read of the server config: the boolean field, or the result of the static call.
                val configIndex = match.instructionMatches.first().index
                val register = getInstruction<OneRegisterInstruction>(configIndex).registerA

                addInstructions(
                    configIndex + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->useFullscreenOverlay(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        // In the overlay YouTube refuses most panels unless a server config allows them.
        PanelRefusedFingerprint.let {
            it.method.apply {
                val readIndex = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(readIndex).registerA

                addInstructions(
                    readIndex + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->useFullscreenOverlay(Z)Z
                        move-result v$register
                    """
                )
            }
        }
    }
}

/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.orientation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.getPlayerTypeFingerprint

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PlayerOrientationPatch;"

@Suppress("unused")
val playerOrientationPatch = patch(
    name = "Player orientation",
    description = "Adds options to control player screen orientation behavior.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        playerOverlayButtonsHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_player_orientation")
        )

        getPlayerTypeFingerprint().method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->" +
                    "onPlayerTypeChanged(Ljava/lang/Enum;)V"
        )

        addPlayerBottomButton(EXTENSION_CLASS)
    }
}

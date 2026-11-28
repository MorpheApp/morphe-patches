/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5441
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.cast

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/RestoreOldCastPatch;"

@Suppress("unused")
val restoreOldCastPatch = patch(
    name = "Restore old Cast behavior",
    description = "Adds an option to keep the Cast session connected when selecting a different video " +
            "or closing the player, so videos keep playing on the TV."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_restore_old_cast_behavior", summary = true)
        )

        CastDisconnectOnVideoSelectFeatureFlagFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->disconnectCastOnVideoSelect(Z)Z"
            )
        }

        if (is_21_30_or_greater) {
            CastSwipeDownTargetFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->dismissPlayerOnSwipeWhileCasting(Z)Z"
                )
            }
        }

        if (is_21_28_or_greater) {
            CastDisconnectOnPlayerCloseFeatureFlagFingerprint.matchAllMethodIndicesForEach { index ->
                insertLiteralOverride(index, "$EXTENSION_CLASS->disconnectCastOnPlayerClose(Z)Z")
            }
        }
    }
}

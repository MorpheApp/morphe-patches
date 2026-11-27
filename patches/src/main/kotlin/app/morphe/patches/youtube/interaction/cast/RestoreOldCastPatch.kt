/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.cast

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/RestoreOldCastPatch;"

@Suppress("unused")
val restoreOldCastPatch = patch(
    name = "Restore old Cast behavior",
    description = "Adds an option to keep the Cast session connected and play newly selected videos on the TV instead of switching to companion mode. This patch works with YouTube 21.30 and newer."
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

        if (!is_21_30_or_greater) {
            Logger.getLogger(this::class.java.name).warning(
                "Restore old Cast behavior requires YouTube 21.30 or newer"
            )
            return@execute
        }

        CastCompanionFeatureFlagFingerprint.matchAllMethodIndicesForEach { index ->
            insertLiteralOverride(index, "$EXTENSION_CLASS->useCastCompanion(Z)Z")
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.disableupdates

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.headerhook.addHeaderHook
import app.morphe.patches.youtube.misc.headerhook.cronetHeaderHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableLayoutUpdatesPatch;"

@Suppress("unused")
val disableLayoutUpdatesPatch = bytecodePatch(
    name = "Disable layout updates",
    description = "Adds an option to disable server side layout updates and use an older UI.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        cronetHeaderHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_disable_layout_updates", summary = true)
        )

        addHeaderHook("$EXTENSION_CLASS->disableLayoutUpdates(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
    }
}

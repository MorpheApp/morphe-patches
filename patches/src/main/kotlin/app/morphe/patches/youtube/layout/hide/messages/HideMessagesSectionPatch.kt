/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.messages

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/MessagesSectionFilter;"

@Suppress("unused")
val hideMessagesSectionPatch = bytecodePatch(
    name = "Hide messages section",
    description = "Adds an option to hide the Messages section shown at the top of the Notifications tab."
) {
    dependsOn(
        sharedExtensionPatch,
        lithoFilterPatch,
        navigationBarHookPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_hide_messages_section", summary = true)
        )

        addLithoFilter(EXTENSION_FILTER)
    }
}

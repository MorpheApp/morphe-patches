/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.patch.patch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.navigation.addBrowseIdHook
import app.morphe.patches.youtube.misc.navigation.addSearchResultsHook
import app.morphe.patches.youtube.misc.navigation.browseIdHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.toolbar.hookToolBar
import app.morphe.patches.youtube.misc.toolbar.toolBarHookPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ChannelSearchPatch;"

val channelSearchPatch = patch(
    name = "Channel search",
    description = "Adds an option to search inside the channel that is currently open " +
            "instead of searching all of YouTube.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch,
        toolBarHookPatch,
        browseIdHookPatch,
        patch {
            execute {
                copyResources(
                    "channelsearch",
                    ResourceGroup("drawable", "morphe_channel_search_button.xml")
                )
            }
        }
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_channel_search", summary = true)
        )

        // Add a dedicated channel-search action while keeping YouTube's original icon global.
        hookToolBar("$EXTENSION_CLASS->setSearchButtonView")

        // A channel page browses by its channel id, which is what the search is scoped to.
        addBrowseIdHook("$EXTENSION_CLASS->setBrowseId(Ljava/lang/String;)V")

        // Leaving a channel for the search feed shows no browse page, so nothing would
        // otherwise replace the browse id of the channel.
        addSearchResultsHook("$EXTENSION_CLASS->clearBrowseId()V")
    }
}

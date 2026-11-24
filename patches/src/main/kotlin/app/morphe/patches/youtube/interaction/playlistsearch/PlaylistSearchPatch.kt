/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5200
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.playlistsearch

import app.morphe.patcher.patch.patch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.navigation.addBrowseIdHook
import app.morphe.patches.youtube.misc.navigation.addSearchResultsHook
import app.morphe.patches.youtube.misc.navigation.browseIdHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/PlaylistSearchPatch;"

@Suppress("unused")
val playlistSearchPatch = patch(
    name = "Playlist search",
    description = "Adds a button to playlist pages to search the videos of that playlist.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch,
        browseIdHookPatch,
        patch {
            execute {
                copyResources(
                    "playlistsearch",
                    ResourceGroup("drawable", "morphe_playlist_search_button.xml")
                )
            }
        }
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_playlist_search", summary = true)
        )

        // A playlist page browses by the playlist id, which is what the search is scoped to.
        addBrowseIdHook("$EXTENSION_CLASS->setBrowseId(Ljava/lang/String;)V")

        // Leaving a playlist for the search feed shows no browse page, so nothing would
        // otherwise replace the browse id of the playlist.
        addSearchResultsHook("$EXTENSION_CLASS->clearBrowseId()V")

        // A playlist page shows its search in an action bar of its own, not in the toolbar of the app.
        ActionBarCreateMenuFingerprint.let {
            it.method.addInstructionsAtControlFlowLabel(
                it.instructionMatches.last().index,
                "invoke-static { p0 }, $EXTENSION_CLASS->addSearchMenuItem(Landroid/view/Menu;)V"
            )
        }
    }
}

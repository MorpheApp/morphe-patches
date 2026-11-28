/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5171
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.playlistNavigationIntentFingerprint
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.playlistNavigationSingletonFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ReversePlaylistAutoplayPatch;"

/**
 * Lets the extension autoplay the previous video of a playlist in place of the next video.
 */
internal val reversePlaylistAutoplayHookPatch = patch {
    dependsOn(
        sharedExtensionPatch,
        playlistRepeatModeHookPatch,
        videoInformationPatch,
    )

    execute {
        val enumType = NavigationIntentEnumFingerprint.originalClassDef.type
        val requestType = NavigationRequestFingerprint.originalClassDef.type

        // The app's own request for the Previous button.
        val previousIntent = playlistNavigationIntentFingerprint(enumType, "PREVIOUS")
            .instructionMatches.last().fieldReference
        val previousRequest = playlistNavigationSingletonFingerprint(requestType, previousIntent)
            .instructionMatches.last().fieldReference

        // A playlist is loaded into the playback queue, which picks the next video for AUTOPLAY and
        // the previous video for PREVIOUS, and repeats the playlist in both directions.
        // Swapping the request only here keeps the rest of the AUTOPLAY handling.
        QueueNavigationItemFingerprint.let {
            val enumField = it.instructionMatches.first().fieldReference

            it.method.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v0, v0, $enumField
                    invoke-static { v0 }, $EXTENSION_CLASS->shouldPlayPrevious(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :original
                    sget-object v0, $previousRequest
                    move-object/from16 p1, v0
                    :original
                    nop
                """
            )
        }
    }
}

@Suppress("unused")
val reversePlaylistAutoplayPatch = patch(
    name = "Reverse playlist autoplay",
    description = "Adds an option to autoplay playlists in reverse order, from the last video to the first.",
) {
    dependsOn(
        settingsPatch,
        reversePlaylistAutoplayHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_reverse_playlist_autoplay", summary = true)
        )
    }
}

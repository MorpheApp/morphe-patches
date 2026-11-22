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
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ReversePlaylistAutoplayPatch;"

/**
 * Lets the extension autoplay the previous video of a playlist in place of the next video.
 */
internal val reversePlaylistAutoplayHookPatch = patch {
    dependsOn(
        sharedExtensionPatch,
        playlistRepeatModeHookPatch,
    )

    execute {
        // A playlist is loaded into the playback queue, which picks the next video for AUTOPLAY and
        // the previous video for PREVIOUS, and repeats the playlist in both directions.
        // Swapping the request only here keeps the rest of the AUTOPLAY handling.
        val enumType = NavigationIntentEnumFingerprint.originalClassDef.type
        val requestConstructor = NavigationRequestFingerprint.originalMethod
        val requestType = requestConstructor.definingClass
        val requestParameters = requestConstructor.parameterTypes.joinToString("")

        queueNavigationItemFingerprint(requestType, enumType).let {
            val enumField = it.instructionMatches.first().fieldReference

            it.method.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v1, v0, $enumField
                    invoke-static { v1 }, $EXTENSION_CLASS->getNavigationIntent(Ljava/lang/Enum;)Ljava/lang/Enum;
                    move-result-object v2
                    if-eq v1, v2, :original
                    check-cast v2, $enumType
                    new-instance v0, $requestType
                    # The queue reads only the intent and video of the request, and PREVIOUS has no video.
                    const/4 v1, 0x0
                    invoke-direct { v0, v2, v1, v1 }, $requestType-><init>($requestParameters)V
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

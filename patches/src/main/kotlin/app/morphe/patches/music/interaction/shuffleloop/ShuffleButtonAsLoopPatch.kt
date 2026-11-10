/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.shuffleloop

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.medianotification.mediaNotificationControlsPatchMusic
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/ShuffleButtonAsLoopPatch;"

@Suppress("unused")
val shuffleButtonAsLoopPatch = bytecodePatch(
    name = "Use loop button instead of shuffle",
    description = "Adds an option to replace the shuffle button of the media controls " +
            "(notification shade and lock screen) with the loop button."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // The option is added to the notification media controls screen,
        // which this patch declares.
        mediaNotificationControlsPatchMusic,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // The shuffle and loop actions are custom actions of the media session that the system UI
        // shows as media control buttons. Only a limited number of them fit in the media controls,
        // and the shuffle action is listed before the loop action, so the loop button is never
        // shown while the shuffle button is. Hiding the shuffle action and showing the loop action
        // puts the loop button where the shuffle button was.
        //
        // The methods are only overridden at the start, so the original body still runs and is
        // used when the option is turned off. Both methods take no parameters, so v0 is a local
        // register and can be used for the overridden result.
        ShuffleActionShownFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->replaceShuffleWithLoop()Z
                move-result v0
                if-eqz v0, :shuffle_shown
                const/4 v0, 0x0
                return v0
                :shuffle_shown
                nop
            """
        )

        LoopActionShownFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->replaceShuffleWithLoop()Z
                move-result v0
                if-eqz v0, :loop_shown
                const/4 v0, 0x1
                return v0
                :loop_shown
                nop
            """
        )
    }
}

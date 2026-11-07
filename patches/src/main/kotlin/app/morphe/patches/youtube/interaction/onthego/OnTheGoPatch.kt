/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4511
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.onthego

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.commandrouter.CommandRouterExecuteCommandFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/onthego/OnTheGoPatch;"

private const val EXTENSION_FILTER = "Lapp/morphe/extension/youtube/patches/onthego/OnTheGoFilter;"

@Suppress("unused")
val onTheGoPatch = patch(
    name = "On-the-go player",
    description = "Adds an option to open a panel with large playback controls " +
            "from the On-the-go item of the video player menu."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        elementProtoParserHookPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        lithoFilterPatch,
        flyoutPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_on_the_go", summary = true)
        )

        // The menu item of an account without Premium has the request of the upsell,
        // which is saved to find the command of the menu item when it runs.
        hookElement("$EXTENSION_CLASS->onElementLoaded")

        // Some accounts get a video player menu without the On-the-go item,
        // so the item is added to the menu if the filter finds no item of the app.
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
        addLithoFilter(EXTENSION_FILTER)

        // Commands of Elements components, such as of the menu item, are run by the command router.
        CommandRouterExecuteCommandFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onCommand(Lcom/google/protobuf/MessageLite;)Z
                move-result v0
                if-eqz v0, :run_command
                return-void
                :run_command
                nop
            """
        )
    }
}

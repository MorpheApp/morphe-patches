/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.loop

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.addBridgeMethod
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.playlistautoplay.NativeNavigationStateFingerprint
import app.morphe.patches.youtube.layout.playlistautoplay.NavigationIntentEnumFingerprint
import app.morphe.patches.youtube.layout.playlistautoplay.navigationIntentHook
import app.morphe.patches.youtube.layout.playlistautoplay.navigationMethodFingerprint
import app.morphe.patches.youtube.layout.playlistautoplay.navigationRequestFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.PlayerStatusEnumFingerprint
import app.morphe.patches.youtube.video.information.getPlayerStatusFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/LoopCountPatch;"
private const val EXTENSION_NAVIGATOR_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoopCountPatch$Navigator;"
private const val EXTENSION_MENU_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoopCountPatch$BottomSheetMenu;"

val loopCountPatch = patch(
    name = "Loop count",
    description = "Adds a player settings menu item to choose how many times the app loops a video, " +
            "before it plays the next video. In a playlist, the loop count is remembered for each video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
        flyoutPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            // A screen of its own, so the loop count settings are not sorted between the loop video settings.
            PreferenceScreenPreference(
                key = "morphe_loop_count_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_loop_count", summary = true),
                    SwitchPreference("morphe_loop_count_playlists", summary = true),
                    NonInteractivePreference(
                        key = "morphe_loop_count_clear",
                        // Set by the preference, with the number of saved loop counts.
                        summaryKey = null,
                        tag = "app.morphe.extension.youtube.settings.preference.LoopCountClearPreference",
                        selectable = true
                    )
                )
            )
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // The menu item is added to the menu that has the loop video item of the app.
        LoopVideoMenuItemKeyFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->loopVideoMenuItemCreated()V"
        )

        // The app closes the menu after an item of it is tapped.
        // The menu that shows the loop count stays open after the loop video item is tapped,
        // so the loop count can be chosen right after.
        BottomSheetMenuCloseFingerprint.let {
            it.classDef.interfaces.add(EXTENSION_MENU_INTERFACE)
            it.classDef.addBridgeMethod(
                "patch_closeMenu",
                emptyList(),
                "V",
                0,
                """
                    invoke-virtual { p0 }, ${it.originalMethod}
                    return-void
                """
            )

            it.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS->closeMenu($EXTENSION_MENU_INTERFACE)Z
                    move-result v0
                    if-nez v0, :close_menu
                    return-void
                    :close_menu
                    nop
                """
            )
        }

        // Counts the plays of the video when it ends. First, before the hooks that loop the video
        // and skip the rest of the player status change, so the loop stops once the video played enough times.
        getPlayerStatusFingerprint(PlayerStatusEnumFingerprint.originalClassDef.type).method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onPlayerStatus(Ljava/lang/Enum;)V"
        )

        // The repeat mode of the app, which is 2 if the app loops the current video.
        NativeNavigationStateFingerprint.method.addInstructions(
            0,
            "invoke-static { p3 }, $EXTENSION_CLASS->setRepeatMode(I)V"
        )

        navigationIntentHook(EXTENSION_CLASS, "onNavigation")

        val requestConstructor = navigationRequestFingerprint(
            NavigationIntentEnumFingerprint.originalClassDef.type
        ).originalMethod
        val requestType = requestConstructor.definingClass
        val setRepeatMode = navigatorSetRepeatModeFingerprint(
            requestConstructor.parameterTypes.drop(1).map { it.toString() }
        ).originalMethod

        // After the navigation hook, as the hook can replace the navigation methods.
        // Each navigator gives itself to the extension before the navigation hook of this patch runs.
        val setNavigatorInstruction = "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                "setNavigator($EXTENSION_NAVIGATOR_INTERFACE)V"
        val navigatorTypes = mutableSetOf<String>()
        navigationMethodFingerprint(requestType).matchAll().forEach { match ->
            if (navigatorTypes.add(match.originalClassDef.type)) {
                val navigatorClass = match.classDef

                navigatorCanNavigateFingerprint(requestType).match(navigatorClass)
                    .method.addInstruction(0, setNavigatorInstruction)

                navigatorClass.interfaces.add(EXTENSION_NAVIGATOR_INTERFACE)
                navigatorClass.addBridgeMethod(
                    "patch_setNavigatorRepeatMode",
                    listOf("I"),
                    "V",
                    0,
                    """
                        invoke-interface { p0, p1 }, $setRepeatMode
                        return-void
                    """
                )
            }

            match.method.addInstruction(0, setNavigatorInstruction)
        }
    }
}

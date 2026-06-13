/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.toPublicAccessFlags

@Suppress("unused")
val disableFullscreenGesturesPatch = bytecodePatch(
    name = "Disable fullscreen gestures",
    description = "Adds options to selectively disable gestures for entering and exiting fullscreen mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    // Cannot declare as top level since this patch is in the same package as
    // other patches that declare same constant name with internal visibility.
    @Suppress("LocalVariableName")
    val EXTENSION_CLASS =
        "Lapp/morphe/extension/youtube/patches/DisableFullscreenGesturesPatch;"

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_disable_fullscreen_gestures",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_disable_fullscreen_pulled_up_gesture", summary = false),
                    SwitchPreference("morphe_disable_fullscreen_dragged_down_gesture", summary = false),
                    SwitchPreference("morphe_disable_fullscreen_sliding_down_gesture", summary = false),
                )
            )
        )

        val playerDragGestureTypeMethod = PlayerDragGestureTypeFingerprint.method.toMutable()

        PlayerDragGestureTypeFingerprint.classDef.methods.apply {
            removeIf {
                it.accessFlags == playerDragGestureTypeMethod.accessFlags &&
                        it.name == playerDragGestureTypeMethod.name &&
                        it.parameters == playerDragGestureTypeMethod.parameters
            }
            add(playerDragGestureTypeMethod.toMutable().apply {
                accessFlags = accessFlags.toPublicAccessFlags()
            })
        }

        PlayerDragGestureInitFingerprint.apply {
            val patchIndex = instructionMatches.last().index
            val freeRegister = method.getFreeRegisterProvider(patchIndex, 1).getFreeRegister()

            method.addInstructionsAtControlFlowLabel(
                patchIndex,
                """
                    invoke-static { p4 }, ${playerDragGestureTypeMethod.definingClass}->${playerDragGestureTypeMethod.name}(I)Ljava/lang/String;
                    move-result-object v$freeRegister
                    invoke-static { v$freeRegister }, $EXTENSION_CLASS->disableFullscreenGestures(Ljava/lang/String;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :disable_fullscreen_gesture
                    const/4 v$freeRegister, 0x0
                    return v$freeRegister
                    :disable_fullscreen_gesture
                    nop
                """
            )
        }
    }
}

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
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.toPublicAccessFlags

@Suppress("unused")
val disableFullscreenGesturePatch = bytecodePatch(
    name = "Disable fullscreen gesture",
    description = "Adds option to disable gesture to enter/exit fullscreen mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    // Cannot declare as top level since this patch is in the same package as
    // other patches that declare same constant name with internal visibility.
    @Suppress("LocalVariableName")
    val EXTENSION_CLASS =
        "Lapp/morphe/extension/youtube/patches/DisableFullscreenGesturePatch;"

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_fullscreen_gesture"),
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
                    invoke-static { v$freeRegister }, $EXTENSION_CLASS->disableFullscreenGesture(Ljava/lang/String;)Z
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

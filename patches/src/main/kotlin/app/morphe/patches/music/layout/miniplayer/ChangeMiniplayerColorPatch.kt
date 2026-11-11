/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_32_or_greater
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/ChangeMiniplayerColorPatch;"

internal val miniplayerColorHookPatch = patch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        // The color the miniplayer gets from the theme, put in the field that holds its color.
        val colorFieldReference = MiniPlayerConstructorFingerprint.method.let { method ->
            val colorGreyIndex = method.indexOfFirstInstructionReversedOrThrow(methodCall(name = "getColor"))
            val colorPutIndex = method.indexOfFirstInstructionOrThrow(colorGreyIndex, Opcode.IPUT)
            val colorRegister = method.getInstruction<TwoRegisterInstruction>(colorPutIndex).registerA

            method.addInstruction(
                colorPutIndex,
                "invoke-static { v$colorRegister }, $EXTENSION_CLASS->setDefaultMiniplayerColor(I)V"
            )
            method.getInstruction<ReferenceInstruction>(colorPutIndex + 1).reference
        }

        // The color of the album art, read from the object passed to the method.
        val colorMathIGetReference: FieldReference
        val getColorMath: (Int) -> String
        if (is_9_32_or_greater) {
            SwitchToggleColorFingerprint.let {
                val colorMathFieldReference = it.instructionMatches.last().fieldReference
                colorMathIGetReference = it.instructionMatches[4].fieldReference
                getColorMath = { register -> "iget-object v$register, p1, $colorMathFieldReference" }
            }
        } else {
            SwitchToggleColorLegacyFingerprint.let {
                val colorMathGetterReference = it.instructionMatches.last()
                    .getInstruction<ReferenceInstruction>().reference
                colorMathIGetReference = it.instructionMatches[4].fieldReference
                getColorMath = { register ->
                    """
                        invoke-virtual { p1 }, $colorMathGetterReference
                        move-result-object v$register
                    """
                }
            }
        }

        SwitchToggleColorParentFingerprint.method.apply {
            val insertIndex = 0
            val freeRegister = findFreeRegister(insertIndex)

            // The extension decides the color of the miniplayer, and keeps the color of the album art
            // for the navigation bar and the glass.
            addInstructionsWithLabels(
                insertIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS->changeMiniplayerColor()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :off
                    if-eqz p1, :off
                    ${getColorMath(freeRegister)}
                    check-cast v$freeRegister, ${colorMathIGetReference.definingClass}
                    iget v$freeRegister, v$freeRegister, $colorMathIGetReference
                    invoke-static { v$freeRegister }, $EXTENSION_CLASS->setLastMiniplayerColor(I)I
                    move-result v$freeRegister
                    iput v$freeRegister, p0, $colorFieldReference
                    :off
                    nop
                """
            )
        }

        // Only the 'ytm_color_grey_12' branch (visible tab bar) is patched; the
        // hidden branch uses a different color. The view is published so the
        // extension can repaint without waiting for a relayout.
        NavigationBarTabLayoutFingerprint.let {
            it.method.apply {
                val setBackgroundColorIndex = it.instructionMatches.last().index
                val call = getInstruction<FiveRegisterInstruction>(setBackgroundColorIndex)
                val tabLayoutRegister = call.registerC
                val colorRegister = call.registerD

                addInstructions(
                    setBackgroundColorIndex,
                    """
                        invoke-static { v$tabLayoutRegister, v$colorRegister }, $EXTENSION_CLASS->registerNavigationBar(Landroid/view/View;I)V
                        invoke-static { v$colorRegister }, $EXTENSION_CLASS->overrideNavigationBarColor(I)I
                        move-result v$colorRegister
                    """
                )
            }
        }

        // Hook the watch-while dismiss callback to drop the cached tint.
        // Fingerprint factory is shared with the swipe-to-dismiss patch.
        val musicActivityPeerClass = MusicActivityWidgetFingerprint.instructionMatches[1]
            .fieldReference.definingClass

        watchWhileDismissedFingerprint(musicActivityPeerClass).method.addInstructions(
            0,
            "invoke-static { }, $EXTENSION_CLASS->onMiniplayerDismissed()V"
        )
    }
}

@Suppress("unused")
val changeMiniplayerColorPatch = patch(
    name = "Change miniplayer color",
    description = "Adds an option to change the miniplayer background color to match the fullscreen player."
) {
    dependsOn(miniplayerColorHookPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.addMiniplayerPreferences(
            SwitchPreference("morphe_music_change_miniplayer_color", summary = true),
            SwitchPreference("morphe_music_change_navigation_bar_color", summary = true)
        )
    }
}

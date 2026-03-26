/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/EnableForcedMiniplayerPatch;"

@Suppress("unused")
val enableForcedMiniplayerPatch = bytecodePatch(
    name = "Enable forced miniplayer",
    description = "Adds an option to enable forced miniplayer when switching between music videos, podcasts, or songs."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_enable_forced_miniplayer")
        )

        MinimizedPlayerFingerprint.let {
            val method = it.method
            val invokeIndex = method.indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.name == "booleanValue"
            }

            val moveResultIndex = invokeIndex + 1
            val moveResultInstr = method.getInstruction<OneRegisterInstruction>(moveResultIndex)
            val targetRegister = moveResultInstr.registerA

            method.addInstructions(
                moveResultIndex + 1,
                """
                    invoke-static {v$targetRegister}, $EXTENSION_CLASS_DESCRIPTOR->enableForcedMiniplayerPatch(Z)Z
                    move-result v$targetRegister
                """
            )
        }
    }
}
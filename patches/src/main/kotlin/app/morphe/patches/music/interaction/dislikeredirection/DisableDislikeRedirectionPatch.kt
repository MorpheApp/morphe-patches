/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1962
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.dislikeredirection

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/DisableDislikeRedirectionPatch;"

@Suppress("unused")
val disableDislikeRedirectionPatch = bytecodePatch(
    name = "Disable dislike redirection",
    description = "Adds an option to prevent skipping to the next track when the dislike " +
            "button is pressed."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_disable_dislike_redirection", summary = true)
        )

        // The notification and player handlers share the same onClick dispatch interface method,
        // extract its reference here to locate the twin call inside the player handler below.
        val onClickReference = NotificationLikeButtonOnClickListenerFingerprint.let {
            it.method.let { method ->
                val onClickIndex = it.instructionMatches.last().index
                val reference = method.getInstruction<ReferenceInstruction>(onClickIndex)
                    .reference

                method.injectRedirectionGuard(onClickIndex)
                reference
            }
        }

        DislikeButtonOnClickListenerFingerprint.method.apply {
            val onClickIndex = indexOfFirstInstructionOrThrow {
                getReference<MethodReference>() == onClickReference
            }
            injectRedirectionGuard(onClickIndex)
        }
    }
}

// Register from the existing IF_EQZ is reused for move-result; both branches
// reassign it before the next read, so the clobber is safe.
private fun MutableMethod.injectRedirectionGuard(onClickIndex: Int) {
    val targetIndex = indexOfFirstInstructionReversedOrThrow(onClickIndex, Opcode.IF_EQZ)
    val insertRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

    addInstructionsWithLabels(
        targetIndex + 1,
        """
            invoke-static { }, $EXTENSION_CLASS->disableDislikeRedirection()Z
            move-result v$insertRegister
            if-nez v$insertRegister, :disable
        """,
        ExternalLabel("disable", getInstruction(onClickIndex + 1))
    )
}

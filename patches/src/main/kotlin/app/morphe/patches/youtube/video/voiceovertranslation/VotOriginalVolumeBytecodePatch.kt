/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s) (based on contributions):
 * - Jav1x (https://github.com/Jav1x)
 * - anddea (https://github.com/anddea)
 *
 * Ported to morphe-patches: https://github.com/MorpheApp/morphe-patches
 * Modified by: Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.methodCall
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/voiceovertranslation/VotOriginalVolumePatch;"

private const val AUDIO_TRACK_CLASS = "Landroid/media/AudioTrack;"

private fun MethodReference.isAudioTrackSetVolume(): Boolean =
    definingClass == AUDIO_TRACK_CLASS &&
        name == "setVolume" &&
        parameterTypes.toList() == listOf("F") &&
        returnType == "I"

private fun getVolumeRegister(instruction: Instruction): Int? {
    return when (instruction) {
        is FiveRegisterInstruction -> if (instruction.registerCount >= 2) instruction.registerD else null
        is TwoRegisterInstruction -> instruction.registerB
        is RegisterRangeInstruction -> if (instruction.registerCount >= 2)
            instruction.startRegister + instruction.registerCount - 1 else null
        else -> null
    }
}

private fun getAudioTrackRegister(instruction: Instruction): Int? {
    return when (instruction) {
        is FiveRegisterInstruction -> if (instruction.registerCount >= 1) instruction.registerC else null
        is TwoRegisterInstruction -> instruction.registerA
        is RegisterRangeInstruction -> if (instruction.registerCount >= 1) instruction.startRegister else null
        else -> null
    }
}

private object AudioTrackSetVolumeMethodFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(methodCall(
        definingClass = "Landroid/media/AudioTrack;",
        name = "setVolume",
        parameters = listOf("F"),
        returnType = "I"
    ))
)

@Suppress("unused")
val votOriginalVolumeBytecodePatch = bytecodePatch(
    name = "Voice Over Translation - Original Volume",
    description = "Hooks AudioTrack.setVolume to duck original audio when VOT translation is playing.",
) {
    dependsOn(voiceOverTranslationBytecodePatch)

    execute {
        val mutableMethod = AudioTrackSetVolumeMethodFingerprint.method
        val index = mutableMethod.indexOfFirstInstructionOrThrow {
            (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                (getReference<MethodReference>()?.isAudioTrackSetVolume() == true)
        }
        val instruction = mutableMethod.implementation!!.instructions.elementAt(index)
        val audioTrackReg = getAudioTrackRegister(instruction)
            ?: throw PatchException("VotOriginalVolume: cannot get AudioTrack register")
        val volReg = getVolumeRegister(instruction)
            ?: throw PatchException("VotOriginalVolume: cannot get volume register")
        mutableMethod.addInstructions(index, """
            invoke-static { v$audioTrackReg, v$volReg }, $EXTENSION_CLASS_DESCRIPTOR->applyVolumeMultiplier(Landroid/media/AudioTrack;F)F
            move-result v$volReg
            """.trimIndent()
        )
    }
}

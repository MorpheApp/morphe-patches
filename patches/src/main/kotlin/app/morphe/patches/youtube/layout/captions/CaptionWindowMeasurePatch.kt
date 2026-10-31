/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * The subtitle window keeps the height of its measure spec if the width mode is EXACTLY,
 * instead of the height mode. Use the height mode for the height.
 */
internal val captionWindowMeasurePatch = bytecodePatch(
    description = "Fixes the caption window height using the width measure mode.",
) {
    execute {
        CaptionWindowMeasureFingerprint.let {
            val heightModeRegister = it.instructionMatches[4]
                .getInstruction<OneRegisterInstruction>().registerA
            val branchIndex = it.instructionMatches.last().index
            val branch = it.method.getInstruction<BuilderInstruction22t>(branchIndex)

            it.method.replaceInstruction(
                branchIndex,
                BuilderInstruction22t(
                    branch.opcode,
                    heightModeRegister,
                    branch.registerB,
                    branch.target
                )
            )
        }
    }
}

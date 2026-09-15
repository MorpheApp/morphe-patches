/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val deviceComplianceCheckPatch = bytecodePatch {

    execute {
        // GmsCore answering "not compliant" sends the app to the background and opens
        // UncertifiedDeviceActivity. A failed check already emits false, so do the same.
        GmsDeviceComplianceCheckFingerprint.method.apply {
            val emitterIndex = GmsDeviceComplianceCheckFingerprint.instructionMatches.last().index
            val emitterReference = getInstruction(emitterIndex).getReference<MethodReference>()!!

            addInstructions(
                0,
                """
                    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                    invoke-virtual { p1, v0 }, $emitterReference
                    return-void
                """
            )
        }
    }
}

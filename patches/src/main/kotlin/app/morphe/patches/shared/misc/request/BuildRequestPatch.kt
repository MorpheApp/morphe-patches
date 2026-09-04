/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.request

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.registersUsed
import java.lang.ref.WeakReference

private lateinit var buildRequestMethod: WeakReference<MutableMethod>
private var builderIndex = -1
private var urlRegister = -1
private var mapRegister = -1

internal val buildRequestPatch = bytecodePatch(
    description = "buildRequestPatch",
) {
    execute {
        getBuildRequestFingerprint().let {
            it.method.apply {
                val builderMatch = it.instructionMatches.first()
                builderIndex = builderMatch.index
                urlRegister = builderMatch.instruction.registersUsed[1]
                mapRegister = it.instructionMatches[1].instruction.registersUsed[0]
                buildRequestMethod = WeakReference(this)
            }
        }
    }
}

internal fun hookBuildRequest(
    descriptor: String,
    hookHeader: Boolean = false
) {
    buildRequestMethod.get()!!.apply {
        if (hookHeader) {
            addInstructions(
                builderIndex,
                """
                    invoke-static { v$urlRegister, v$mapRegister }, $descriptor(Ljava/lang/String;Ljava/util/Map;)Ljava/util/Map;
                    move-result-object v$mapRegister
                """
            )
            builderIndex += 2
        } else {
            addInstructions(
                builderIndex++,
                "invoke-static { v$urlRegister, v$mapRegister }, $descriptor(Ljava/lang/String;Ljava/util/Map;)V"
            )
        }
    }
}

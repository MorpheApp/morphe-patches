/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.request

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.registersUsed
import java.lang.ref.WeakReference

private lateinit var buildRequestMethod: WeakReference<MutableMethod>
private var builderIndex = -1
private var urlRegister = -1
private var mapRegister = -1
private var bodyRegister = -1

internal val buildRequestPatch = patch(
    description = "buildRequestPatch",
) {
    execute {
        BuildRequestFingerprint.let {
            it.method.apply {
                val builderMatch = it.instructionMatches.first()
                builderIndex = builderMatch.index
                urlRegister = builderMatch.instruction.registersUsed[1]
                bodyRegister = it.instructionMatches[1].instruction.registersUsed[0]
                mapRegister = it.instructionMatches[2].instruction.registersUsed[0]
                buildRequestMethod = WeakReference(this)
            }
        }
    }
}

/**
 * @param hookHeader If the hook returns the headers to send.
 * @param includeBody If the hook is also given the body of the request, which is null if it has none.
 */
internal fun hookBuildRequest(
    descriptor: String,
    hookHeader: Boolean = false,
    includeBody: Boolean = false
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
        } else if (includeBody) {
            addInstructions(
                builderIndex++,
                "invoke-static { v$urlRegister, v$mapRegister, v$bodyRegister }, $descriptor(Ljava/lang/String;Ljava/util/Map;[B)V"
            )
        } else {
            addInstructions(
                builderIndex++,
                "invoke-static { v$urlRegister, v$mapRegister }, $descriptor(Ljava/lang/String;Ljava/util/Map;)V"
            )
        }
    }
}

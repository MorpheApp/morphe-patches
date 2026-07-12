/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.request

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import java.lang.ref.WeakReference

private lateinit var buildRequestMethod: WeakReference<MutableMethod>
private var builderIndex = -1
private var urlRegister = -1
private var freeRegister = -1

internal val buildRequestPatch = bytecodePatch(
    description = "buildRequestPatch",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        buildRequestMethod = WeakReference(BuildRequestFingerprint.method.apply {
            builderIndex = indexOfNewUrlRequestBuilderInstruction(this)
            urlRegister = getInstruction<FiveRegisterInstruction>(builderIndex).registerD
            freeRegister = findFreeRegister(builderIndex, urlRegister)

            if (!getInstruction(builderIndex).toString().contains("move-object v$freeRegister, p1")) {
                addInstruction(builderIndex, "move-object v$freeRegister, p1")
            }
        })
    }
}

internal fun hookBuildRequest(descriptor: String) {
    buildRequestMethod.get()!!.apply {
        addInstruction(
            builderIndex + 1,
            "invoke-static { v$urlRegister, v$freeRegister }, $descriptor"
        )
    }
}

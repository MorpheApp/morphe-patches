/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.request

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private lateinit var buildRequestMethod: MutableMethod
private var builderIndex = 0
private var urlRegister = 0
private var freeRegister = 0

internal val buildRequestPatch = bytecodePatch(
    description = "buildRequestPatch",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        buildRequestMethod = BuildRequestFingerprint.method.apply {
            builderIndex = indexOfNewUrlRequestBuilderInstruction(this)
            urlRegister = getInstruction<FiveRegisterInstruction>(builderIndex).registerD
            freeRegister = findFreeRegister(builderIndex, urlRegister)

            if (!getInstruction(builderIndex).toString().contains("move-object v$freeRegister, p1"))
                addInstruction(builderIndex, "move-object v$freeRegister, p1")
        }
    }
}

internal fun hookBuildRequest(descriptor: String) {
    buildRequestMethod.apply {
        addInstruction(builderIndex + 1, "invoke-static { v$urlRegister, v$freeRegister }, $descriptor")
    }
}

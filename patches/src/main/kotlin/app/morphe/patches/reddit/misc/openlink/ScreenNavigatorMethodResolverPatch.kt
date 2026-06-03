/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_11_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.lang.ref.WeakReference

lateinit var screenNavigatorMethodRef: WeakReference<MutableMethod>

val screenNavigatorMethodResolverPatch = bytecodePatch(
    description = "screenNavigatorMethodResolverPatch"
) {
    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        var targetMethod = CustomReportsFingerprint.instructionMatches[2].getMethodCalled()

        targetMethod.apply {
            if (is_2026_11_0_or_greater) {
                val targetIndex = indexOfFirstInstructionOrThrow {
                    val targetReference = getReference<MethodReference>()
                    targetReference?.returnType == "V" &&
                            targetReference.parameterTypes.firstOrNull() == "Landroid/app/Activity;"
                }

                targetMethod = getInstruction<ReferenceInstruction>(targetIndex)
                    .getReference<MethodReference>()!!
                    .getMutableMethod()
            }
        }

        screenNavigatorMethodRef = WeakReference(targetMethod)
    }
}

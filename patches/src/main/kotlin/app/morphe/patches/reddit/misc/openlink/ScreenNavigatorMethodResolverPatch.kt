package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

lateinit var screenNavigatorMethod: MutableMethod

val screenNavigatorMethodResolverPatch = bytecodePatch(
    description = "screenNavigatorMethodResolverPatch"
) {
    execute {
        screenNavigatorMethod =
                // ~ Reddit 2024.25.3
            screenNavigatorFingerprint.methodOrNull
                    // Reddit 2024.26.1 ~
                ?: with(customReportsFingerprint.method) {
                    val offset = indexOfScreenNavigatorInstruction(this)
                    val newMethod = getInstruction<ReferenceInstruction>(offset).reference as MethodReference
                    mutableClassDefBy(newMethod.definingClass).findMutableMethodOf(newMethod)
                }
    }
}

package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

lateinit var screenNavigatorMethod: MutableMethod

val screenNavigatorMethodResolverPatch = bytecodePatch(
    description = "screenNavigatorMethodResolverPatch"
) {
    execute {
        val targetMethod = CustomReportsFingerprint.instructionMatches[3]
            .instruction.getReference<MethodReference>()!!

        screenNavigatorMethod =
            mutableClassDefBy(targetMethod.definingClass).findMutableMethodOf(targetMethod)
    }
}

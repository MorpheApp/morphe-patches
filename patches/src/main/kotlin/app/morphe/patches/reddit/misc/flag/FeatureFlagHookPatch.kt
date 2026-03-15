/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.flag

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneMutable
import java.lang.ref.WeakReference

private lateinit var featureFlagMethod : WeakReference<MutableMethod>

val featureFlagHookPatch = bytecodePatch(
    description = "featureFlagHookPatch"
) {
    dependsOn(sharedExtensionPatch)

    execute {
        FeatureFlagFingerprint.match(
            FeatureFlagParentFingerprint.originalClassDef
        ).let {
            it.method.apply {
                // Not enough registers in the method. Clone the method and use the
                // original method as an intermediate to call extension code.

                // Copy the method.
                val helperMethod = cloneMutable(name = "patch_getBooleanFeatureFlag")

                // Add the method.
                it.classDef.methods.add(helperMethod)

                addInstructions(
                    0,
                    """
                        # Invoke the copied method (helper method).
                        invoke-virtual { p0, p1, p2 }, $helperMethod
                        move-result p2
                        
                        # Since the copied method (helper method) has already been invoked, it just returns.
                        return p2
                    """
                )

                featureFlagMethod = WeakReference(this)
            }
        }
    }
}

internal fun hookFeatureFlag(descriptor: String) = featureFlagMethod.get()!!.addInstructions(
    2,
    """
        invoke-static { p1, p2 }, $descriptor(Ljava/lang/String;Z)Z
        move-result p2
    """
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.flag

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.util.cloneMutable
import java.lang.ref.WeakReference

private lateinit var featureFlagMethod : WeakReference<MutableMethod>

val featureFlagHookPatch = patch(
    description = "featureFlagHookPatch"
) {
    dependsOn(sharedExtensionPatch, versionCheckPatch)

    execute {
        // 2026.47.0+ reads the experiment in the feature flag reader,
        // and the experiments class no longer has a boolean method.
        val fingerprint = if (is_2026_47_0_or_greater) FeatureFlagFingerprint
        else FeatureFlagLegacyFingerprint

        fingerprint.let {
            it.method.apply {
                // Not enough registers in the method. Clone the method and use the
                // original method as an intermediate to call extension code.

                // Copy the method.
                val helperMethod = cloneMutable(name = "patch_getBooleanFeatureFlag")

                // Add the method.
                it.classDef.methods.add(helperMethod)

                // The second parameter is a boolean in all versions, and it is reused for the result.
                val lastRegister = "p${parameters.size}"

                addInstructions(
                    0,
                    """
                        # Invoke the copied method (helper method).
                        invoke-virtual/range { p0 .. $lastRegister }, $helperMethod
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

/**
 * ## Important
 * Hooked code must check if context is set or not, as this hook can be called before
 * the main activity has launched.
 */
internal fun hookFeatureFlag(descriptor: String) = featureFlagMethod.get()!!.addInstructions(
    2,
    """
        invoke-static { p1, p2 }, $descriptor(Ljava/lang/String;Z)Z
        move-result p2
    """
)

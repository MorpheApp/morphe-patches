/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.media

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal fun mediaFetchPlayerConfigPatch(
    extensionClass: String,
    hasMediaSessionFeatureFlag: BytecodePatchBuilder.() -> Boolean,
    highPriority: Boolean = false
) = bytecodePatch(
    description = "Hooks media fetch player config.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        setOf(
            Triple(
                MediaFetchHotConfigFingerprint,
                true,
                "useMediaFetchHotConfigReplacement"
            ),
            Triple(
                MediaSessionFeatureFlagFingerprint,
                hasMediaSessionFeatureFlag(),
                "useMediaSessionFeatureFlag"
            )
        ).forEach { (fingerprint, hasConfig, methodName) ->
            if (hasConfig) {
                fingerprint.let {
                    it.clearMatch()
                    it.method.apply {
                        val index = it.instructionMatches[1].index
                        val register = getInstruction<OneRegisterInstruction>(index).registerA

                        val operation = if (register < 16) {
                            "invoke-static { v$register }"
                        } else {
                            "invoke-static/range { v$register .. v$register }"
                        }

                        addInstructions(
                            if (highPriority) index + 1 else it.instructionMatches[2].index,
                            """
                                $operation, $extensionClass->$methodName(Z)Z
                                move-result v$register
                                """
                        )
                    }
                }
            }
        }
    }
}

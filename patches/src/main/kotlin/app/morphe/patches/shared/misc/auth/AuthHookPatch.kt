/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.request.buildRequestPatch
import app.morphe.patches.shared.misc.request.hookBuildRequest
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/innertube/utils/AuthUtils;"

internal fun authHookPatch(
    emptyPageIdHook: PatchDefinition.() -> Boolean,
    block: PatchDefinition.() -> Unit,
    executeBlock: () -> Unit = {},
) = patch(
    description = "Hook to get the parameters required for account authentication."
) {
    block()

    dependsOn(buildRequestPatch)

    execute {
        val pageIdFingerprints = mutableListOf<Fingerprint>(PageIdFingerprint)

        if (emptyPageIdHook()) {
            pageIdFingerprints += IsEmptyPageIdFingerprint
        }

        pageIdFingerprints.forEach {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setPageId(Ljava/lang/String;)V"
                )
            }
        }

        IncognitoStatusFingerprint.matchAll().forEach {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setIncognitoStatus(Z)V"
                )
            }
        }

        hookBuildRequest("$EXTENSION_CLASS->setRequestHeaders")

        executeBlock()
    }
}

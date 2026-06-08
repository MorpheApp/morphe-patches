/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.request.buildRequestPatch
import app.morphe.patches.youtube.misc.request.hookBuildRequest
import app.morphe.util.findFieldFromToString
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/innertube/utils/AuthUtils;"

internal val authHookPatch = bytecodePatch(
    description = "Hook to get the parameters required for account authentication"
) {
    dependsOn(
        sharedExtensionPatch,
        buildRequestPatch,
    )

    execute {
        val (pageIdField, incognitoField) =
            with(AccountIdentityToStringFingerprint.method) {
                Pair(
                    findFieldFromToString(GET_PAGE_ID_STRING),
                    findFieldFromToString(IS_INCOGNITO_STRING)
                )
            }

        AccountIdentityConstructorFingerprint.method.apply {
            val pageIdIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IPUT_OBJECT && getReference<FieldReference>() == pageIdField
            }
            val incognitoIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IPUT_BOOLEAN && getReference<FieldReference>() == incognitoField
            }
            val pageIdRegister = getInstruction<TwoRegisterInstruction>(pageIdIndex).registerA
            val incognitoRegister = getInstruction<TwoRegisterInstruction>(incognitoIndex).registerA

            addInstructions(
                1,
                """
                    invoke-static { v$pageIdRegister }, $EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setPageId(Ljava/lang/String;)V
                    invoke-static { v$incognitoRegister }, $EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setIncognitoStatus(Z)V
                """
            )
        }

        hookBuildRequest("$EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V")
    }
}

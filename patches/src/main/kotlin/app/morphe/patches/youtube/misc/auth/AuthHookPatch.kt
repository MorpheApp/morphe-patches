/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.request.buildRequestPatch
import app.morphe.patches.youtube.misc.request.hookBuildRequest
import app.morphe.util.cloneParameters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/innertube/utils/AuthUtils;"

internal val authHookPatch = bytecodePatch(
    description = "authHookPatch"
) {
    dependsOn(
        sharedExtensionPatch,
        buildRequestPatch,
    )

    execute {
        val accountIdentityDefiningClass = AccountIdentityFingerprint.method.definingClass
        val clearlyClassName = accountIdentityDefiningClass.endsWith($$"$AutoValue_AccountIdentity;")
        val pageIdAccessedField = AccountIdentityFingerprint.instructionMatches[1].getFieldAccessed().toString()
        val incognitoStatusAccessedField = AccountIdentityFingerprint.instructionMatches.last().getFieldAccessed().toString()

        fun returnRegisterType(register: Int): String =
            (if (clearlyClassName) "v" else "p") + register

        buildList{
            if (!clearlyClassName) {
                add(
                    Fingerprint(
                        definingClass = accountIdentityDefiningClass,
                        returnType = "Z",
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        parameters = listOf(),
                        filters = listOf(
                            fieldAccess(
                                opcode = Opcode.IGET_OBJECT,
                                location = InstructionLocation.MatchFirst(),
                                smali = pageIdAccessedField
                            ),
                            opcode(
                                Opcode.CONST_STRING,
                                location = InstructionLocation.MatchAfterImmediately()
                            ),
                            opcode(
                                Opcode.INVOKE_VIRTUAL,
                                location = InstructionLocation.MatchAfterImmediately()
                            ),
                        ),
                    )
                )
            }
            add(
                Fingerprint(
                    definingClass = accountIdentityDefiningClass,
                    returnType = "Ljava/lang/String;",
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                    parameters = listOf(),
                    filters = listOf(
                        fieldAccess(
                            opcode = Opcode.IGET_OBJECT,
                            location = InstructionLocation.MatchFirst(),
                            smali = pageIdAccessedField
                        ),
                        opcode(
                            Opcode.RETURN_OBJECT,
                            location = InstructionLocation.MatchAfterImmediately()
                        ),
                    ),
                )
            )
        }.forEach { fingerprint ->
            val method = fingerprint.method
            val instructionIndex = fingerprint.instructionMatches.first().index

            method.addInstruction(
                instructionIndex + 1,
                """
                invoke-static { ${returnRegisterType(0)} }, $EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setPageId(Ljava/lang/String;)V
            """
            )
        }

        Fingerprint(
            definingClass = accountIdentityDefiningClass,
            returnType = "Z",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            parameters = listOf(),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_BOOLEAN,
                    location = InstructionLocation.MatchFirst(),
                    smali = incognitoStatusAccessedField
                ),
                opcode(Opcode.RETURN, location = InstructionLocation.MatchAfterImmediately()),
            )
        ).matchAll().forEach { fingerprint ->
            val method = fingerprint.method
            val instructionIndex = fingerprint.instructionMatches.first().index

            method.addInstruction(
                instructionIndex + 1,
                """
                invoke-static { ${returnRegisterType(0)} }, $EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setIncognitoStatus(Z)V
            """
            )
        }

        hookBuildRequest("$EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V")
    }
}

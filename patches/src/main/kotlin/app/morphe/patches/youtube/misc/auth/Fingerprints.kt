/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val GET_PAGE_ID_STRING = ", getPageId="
internal const val IS_INCOGNITO_STRING = ", isIncognito="

internal object AccountIdentityToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf(
        GET_PAGE_ID_STRING,
        IS_INCOGNITO_STRING
    )
)

internal fun getIncognitoStatusFingerprint(incognitoField: FieldReference) = object : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            location = MatchFirst(),
            reference = incognitoField
        ),
        opcode(
            opcode = Opcode.RETURN,
            location = MatchAfterImmediately()
        )
    )
) {}

internal fun getPageIdFingerprint(pageIdField: FieldReference) = object : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchFirst(),
            reference = pageIdField
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        )
    )
) {}

internal fun isEmptyPageIdFingerprint(pageIdField: FieldReference) = object : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchFirst(),
            reference = pageIdField
        ),
        string(
            string = "",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            location = MatchAfterImmediately()
        )
    )
) {}

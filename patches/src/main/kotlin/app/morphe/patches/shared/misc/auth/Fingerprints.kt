/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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

internal object IncognitoStatusFingerprint : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            location = MatchFirst(),
            reference = { AccountIdentityToStringFingerprint.method.findFieldFromToString(IS_INCOGNITO_STRING) }
        ),
        opcode(
            opcode = Opcode.RETURN,
            location = MatchAfterImmediately()
        )
    )
)

internal object PageIdFingerprint : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchFirst(),
            reference = { AccountIdentityToStringFingerprint.method.findFieldFromToString(GET_PAGE_ID_STRING) }
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

internal object IsEmptyPageIdFingerprint : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchFirst(),
            reference = { AccountIdentityToStringFingerprint.method.findFieldFromToString(GET_PAGE_ID_STRING) }
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
)

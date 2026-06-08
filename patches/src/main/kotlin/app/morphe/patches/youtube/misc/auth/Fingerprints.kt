/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

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

internal object AccountIdentityConstructorFingerprint : Fingerprint(
    classFingerprint = AccountIdentityToStringFingerprint,
    name = "<init>",
    filters = listOf(
        string("Null getPageId"),
    ),
)

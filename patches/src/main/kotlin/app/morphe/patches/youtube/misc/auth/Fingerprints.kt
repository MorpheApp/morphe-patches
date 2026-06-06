/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

val accountIdentityFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "Null getId",
        "Null getAccountName",
        "Null getPageId",
        "Null getDataSyncId",
        "Null getGaiaDelegationType",
        "Null getDelegationContext"
    ),
    custom = { method, _ ->
        val parameterTypes = method.parameterTypes
        parameterTypes.size > 4 && parameterTypes[2] == "Ljava/lang/String;" && parameterTypes[3] == "Z"
    }
)

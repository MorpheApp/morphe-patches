/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.auth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object AccountIdentityFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "Null getId",
        "Null getAccountName",
        "Null getPageId",
        "Null getDataSyncId",
        "Null getGaiaDelegationType",
        "Null getDelegationContext",
    ),
    filters = listOf(
        opcode(Opcode.IF_EQZ, location = InstructionLocation.MatchAfterAnywhere()),
        opcode(Opcode.IPUT_OBJECT, location = InstructionLocation.MatchAfterImmediately()),
        opcode(Opcode.IPUT_BOOLEAN, location = InstructionLocation.MatchAfterImmediately()),
    ),
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.textcomponent

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object TextComponentContextFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        returnType = "V",
        parameters = listOf(),
        filters = listOf(
            string("TextComponent"),
            opcode(Opcode.SGET_OBJECT),
            opcode(Opcode.IPUT_OBJECT)
        )
    ),
    returnType = "L",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(type = "Ljava/util/Map;"),
    )
//    filters = OpcodesFilter.opcodesToFilters(
//        Opcode.IGET_OBJECT,
//        Opcode.IGET_OBJECT,
//        Opcode.IGET_OBJECT,
//        Opcode.IGET_BOOLEAN
//    )
)

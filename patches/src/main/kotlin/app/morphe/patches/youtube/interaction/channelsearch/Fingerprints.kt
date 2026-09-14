/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Sets the browse id of a browse request, and flags whether it is the home feed.
 */
internal val browseIdSetterFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/String;"),
        string("FEwhat_to_watch"),
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN),
    )
)

/**
 * Every search submit path funnels through this method, including suggestions and filter chips.
 */
internal val searchSubmitFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    ),
    filters = listOf(
        methodCall(
            parameters = listOf(
                "Ljava/lang/String;",
                "[B",
                "Ljava/lang/String;",
                "I",
                "L",
                "L",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Z"
            ),
            returnType = "V"
        )
    )
)

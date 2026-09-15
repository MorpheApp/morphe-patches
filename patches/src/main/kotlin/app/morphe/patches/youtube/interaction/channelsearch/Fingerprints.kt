/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Traces the browse id of a browse request, which is where the field it is kept in can be read
 * from. The setter of that field has no shape of its own to match against.
 */
internal val browseIdTraceFingerprint = Fingerprint(
    strings = listOf("browseId", "language"),
    filters = listOf(
        string("browseId"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
    )
)

/**
 * 20.30 and earlier read the field before the name of it.
 */
internal val browseIdTraceLegacyFingerprint = Fingerprint(
    strings = listOf("browseId", "language"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;"),
        string("browseId", MatchAfterImmediately()),
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

/**
 * 20.30 and earlier take one parameter less, both here and in the search it calls.
 */
internal val searchSubmitLegacyFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;"
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
                "Ljava/lang/String;"
            ),
            returnType = "V"
        )
    )
)

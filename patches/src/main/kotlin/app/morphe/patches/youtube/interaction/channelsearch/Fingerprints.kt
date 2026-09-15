/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.channelsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Traces the browse id of a browse request, which is where the field it is kept in can be read
 * from. The setter of that field has no shape of its own to match against.
 */
internal object BrowseIdTraceFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        string("FEwhat_to_watch"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(10)
        )
    ),
    strings = listOf(
        "Home offline response is only used for Homepage"
    )
)

/**
 * Every search submit path funnels through this method, including suggestions and filter chips.
 */
internal object SearchSubmitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        anyInstruction(
            methodCall( // 21.31+
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
            ),
            methodCall( // 21.30 and older.
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
    ),
    custom = { method, _ ->
        parametersMatch( // 21.31+
            method.parameters,
            listOf(
                "Ljava/lang/String;",
                "I",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Z"
            )
        ) || parametersMatch( // 21.30 and older.
            method.parameters,
            listOf(
                "Ljava/lang/String;",
                "I",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;"
            )
        )
    }
)

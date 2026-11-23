/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5225
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.peoplealsosearchfor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Maps a 'SearchRow' GraphQL element to the app model.
 * Matches the 'SearchQuerySuggestion' type check of the row children.
 */
internal object SearchRowMapperFingerprint : Fingerprint(
    filters = listOf(
        string("SearchRowComponentHeaderPresentation"),
        string("SearchRowOverflowHeaderPresentation"),
        string("SearchQuerySuggestion"),
        // Intrinsics.areEqual(Object, Object), which is obfuscated in some app versions.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately())
    )
)

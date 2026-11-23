/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5189
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.profile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private const val QUERY_CLASS = "Lcom/reddit/domain/model/search/Query;"

internal object ProfileModelToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("ProfileModel(username="),
        // Username.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        string(", isProfileFiltered="),
        // Is filtered (posts or comments hidden by the profile settings).
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            location = MatchAfterWithin(8)
        )
    )
)

/**
 * Click handler of the search button of a profile, that opens search scoped to the profile.
 */
internal object ProfileSearchClickFingerprint : Fingerprint(
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Profile model.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            name = $$"$model"
        ),
        methodCall(
            definingClass = QUERY_CLASS,
            name = "<init>"
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lcom/reddit/domain/model/search/OriginPageType;",
            name = "PROFILE"
        ),
        // Opens search.
        methodCall(
            returnType = "V",
            parameters = listOf(
                "L",
                "Landroid/content/Context;",
                QUERY_CLASS,
                "Lcom/reddit/domain/model/search/SearchCorrelation;"
            )
        )
    )
)

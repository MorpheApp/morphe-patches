/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.returnyoutubedislike

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object EndpointServiceNameFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "L",
    filters = listOf(
        string("serviceName"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        )
    )
)

internal object DislikeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("like/dislike")
    )
)

internal fun requestParameterCheckFingerprint(definingClass: String) = object : Fingerprint(
    definingClass = definingClass,
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        // playlistId
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/lang/String;->isEmpty()Z"
        ),
        // videoId
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;"
        )
    )
) {}

internal fun likeEndpointParserFingerprint(definingClass: String) = object : Fingerprint(
    definingClass = definingClass,
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            location = MatchFirst()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this"
        ),
        string("")
    )
) {}

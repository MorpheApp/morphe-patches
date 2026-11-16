/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * Command resolver of the share entity service endpoint.
 * With a network connection it opens the in-app share sheet (UnifiedSharePanelFragment).
 * Without one, it builds the music.youtube.com link of the song, playlist or artist
 * from the serialized share entity and opens the system share sheet.
 *
 * Matches the endpoint cast, the network check, and the read of the serialized share entity.
 */
internal object ShareEntityServiceEndpointCommandFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf(
        "music.youtube.com",
        "navigation_endpoint",
        "UnifiedSharePanelFragment"
    ),
    filters = listOf(
        opcode(Opcode.CHECK_CAST),
        methodCall(
            returnType = "Z",
            parameters = listOf(),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(5)
        )
    )
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.startpage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object IntentActionFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Intent;"),
    filters = listOf(
        string("has_handled_intent")
    )
)

internal object BrowseIdFingerprint : Fingerprint(
    returnType = "L",

    //parameters() // 20.30 and earlier is no parameters = listOf(.  20.31+ parameter is L.),
    filters = listOf(
        string("FEwhat_to_watch"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/String;"),
            location = MatchAfterImmediately()
        ),
        // Sets the has bit of the browse id, which is 0x400 in 21.47+ and 0x200 before.
        opcode(Opcode.OR_INT_LIT16),
        opcode(Opcode.IPUT, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Prepares the request of the guide, the response with the buttons of the top bar and of the
 * navigation bar. The first instruction reads the flag of the startup milestone that the request
 * waits for. Older app versions have other numbers for the same flags.
 */
internal object GuideRequestFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            literal(1074004527L),
            literal(268698159L)
        ),
        anyInstruction(
            literal(1073880817L),
            literal(268574449L)
        )
    )
)

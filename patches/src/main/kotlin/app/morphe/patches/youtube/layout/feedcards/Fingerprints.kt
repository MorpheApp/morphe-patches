/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3769
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedcards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Runs the command of the watch next response for when the watch page is ready.
 *
 * @param uiReadyCommandMethod Method that runs the command, with the parameters (command, Map, watch next response).
 */
internal class WatchNextUiReadyFingerprint(uiReadyCommandMethod: MethodReference) : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = uiReadyCommandMethod.parameterTypes[2].toString()
        ),
        string("com.google.android.libraries.youtube.innertube.endpoint.tag"),
        string("sectionListController"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = uiReadyCommandMethod.definingClass
        ),
        methodCall(
            reference = uiReadyCommandMethod,
            location = MatchAfterWithin(2)
        )
    )
)

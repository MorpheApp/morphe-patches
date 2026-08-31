/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.videoactionbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patches.shared.BuildInnerTubeProtoRequestUriFingerprint
import app.morphe.patches.youtube.shared.CLIENT_INFO_CLASS
import com.android.tools.smali.dexlib2.Opcode

internal object BuildInnerTubeProtoRequestBodyFingerprint : Fingerprint(
    classFingerprint = BuildInnerTubeProtoRequestUriFingerprint,
    parameters = listOf("L"),
    returnType = "Lcom/google/protobuf/MessageLite;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = CLIENT_INFO_CLASS
        )
    )
)

internal fun getConfigInfoFingerprint(configInfoClass: String) = object : Fingerprint(
    definingClass = configInfoClass,
    name = "<init>",
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        )
    )
) {}



/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
 
package app.morphe.patches.youtube.misc.litho.lazily

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ComponentContextParserFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("Failed to parse Element proto."),
        string("Cannot read theme key from model.")
    )
)

internal object TreeNodeResultListFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    filters = listOf(
        methodCall(name = "nCopies", opcode = Opcode.INVOKE_STATIC),
    )
)

internal object LazilyConvertedElementPatchFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "onLazilyConvertedElementLoaded",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC)
)


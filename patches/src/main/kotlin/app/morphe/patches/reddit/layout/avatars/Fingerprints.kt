/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4477
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.avatars

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Glide RequestBuilder.into(Target, RequestListener, BaseRequestOptions, Executor).
 */
private object GlideRequestBuilderIntoFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("You must call #load() before calling #into()")
    )
)

/**
 * Glide RequestBuilder.loadGeneric(Object), which all load(...) methods use to set the image model.
 */
internal object GlideRequestBuilderLoadGenericFingerprint : Fingerprint(
    classFingerprint = GlideRequestBuilderIntoFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/Object;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            location = MatchAfterWithin(3)
        )
    )
)

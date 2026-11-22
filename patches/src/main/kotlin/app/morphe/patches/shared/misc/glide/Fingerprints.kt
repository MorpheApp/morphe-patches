/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4477
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.glide

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Glide RequestBuilder.into(Target, RequestListener, BaseRequestOptions, Executor).
 */
internal object GlideRequestBuilderIntoFingerprint : Fingerprint(
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

/**
 * Glide ViewTarget.getRequest(), which reads the request from a tag of the view the target loads into.
 */
internal object GlideViewTargetGetRequestFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/view/View;"
        ),
        string("You must not call setTag() on a view Glide is targeting")
    )
)

/**
 * Glide RequestTracker.toString().
 */
internal object GlideRequestTrackerToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("{numRequests=")
    )
)

/**
 * Glide RequestTracker.clearAndRemove(Request), which removes the request and then calls Request.clear().
 */
internal object GlideRequestTrackerClearAndRemoveFingerprint : Fingerprint(
    classFingerprint = GlideRequestTrackerToStringFingerprint,
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(smali = "Ljava/util/Set;->remove(Ljava/lang/Object;)Z"),
        methodCall(smali = "Ljava/util/Set;->remove(Ljava/lang/Object;)Z"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            returnType = "V",
            parameters = listOf()
        )
    )
)

/**
 * Glide SingleRequest.begin(), which starts loading the image again if the request is not running or complete.
 */
internal object GlideSingleRequestBeginFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("Cannot restart a running request")
    )
)

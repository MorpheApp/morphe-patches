/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

internal object NativeBrowseServiceFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(string("browse"))
)

/**
 * Creates a streaming browse request. The method it calls creates every browse request.
 */
internal object NativeStreamingBrowseRequestFingerprint : Fingerprint(
    classFingerprint = NativeBrowseServiceFingerprint,
    parameters = listOf("L"),
    returnType = "L",
    filters = listOf(
        string("streaming_browse"),
        methodCall(
            definingClass = "this",
            parameters = listOf("L", "L", "Ljava/lang/String;"),
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Creates a browse request for the current account. The context parameter can be null.
 */
internal class NativeRequestFactoryFingerprint(requestType: String) : Fingerprint(
    classFingerprint = NativeBrowseServiceFingerprint,
    parameters = listOf("L"),
    returnType = requestType,
    filters = listOf(
        methodCall(
            definingClass = "this",
            parameters = listOf("L", "L"),
            returnType = requestType
        )
    )
)

/**
 * Browse request dispatch that delegates to the dispatch implementation.
 */
internal class NativeBrowseDispatchFingerprint(requestType: String) : Fingerprint(
    classFingerprint = NativeBrowseServiceFingerprint,
    parameters = listOf(requestType, EXECUTOR),
    returnType = LISTENABLE_FUTURE,
    filters = listOf(
        methodCall(
            definingClass = "this",
            parameters = listOf(requestType, EXECUTOR),
            returnType = LISTENABLE_FUTURE
        )
    )
)

/**
 * Matched against the browse service class. Not all versions have both generic dispatch methods.
 */
internal class NativeGenericDispatchFingerprint(requestBaseType: String) : Fingerprint(
    parameters = listOf(requestBaseType, "L", EXECUTOR),
    returnType = LISTENABLE_FUTURE,
    filters = listOf(methodCall(returnType = LISTENABLE_FUTURE))
)

/**
 * 21.23+ also has a generic dispatch with a fourth parameter.
 */
internal class NativeGenericDispatchWithExtraParameterFingerprint(requestBaseType: String) : Fingerprint(
    parameters = listOf(requestBaseType, "L", EXECUTOR, "L"),
    returnType = LISTENABLE_FUTURE,
    filters = listOf(methodCall(returnType = LISTENABLE_FUTURE))
)

/**
 * Browse request debug description. Matches the route and continuation fields.
 */
internal class NativeRequestDescriptionFingerprint(requestType: String) : Fingerprint(
    definingClass = requestType,
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("browseId"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        string("continuation"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
    )
)

/**
 * Debug description of the base class of all requests. Matches the click tracking field.
 */
internal object NativeBaseRequestDescriptionFingerprint : Fingerprint(
    filters = listOf(
        string("serviceName"),
        fieldAccess(
            definingClass = "this",
            opcode = Opcode.IGET_OBJECT,
            type = "[B"
        ),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            type = "[B",
            location = MatchAfterImmediately()
        ),
        string("clickTrackingParams", location = MatchAfterImmediately()),
    )
)

internal class NativeRequestStringSetterFingerprint(
    definingClass: String,
    fieldName: String
) : Fingerprint(
    definingClass = definingClass,
    parameters = listOf("Ljava/lang/String;"),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            name = fieldName,
            type = "Ljava/lang/String;",
            opcode = Opcode.IPUT_OBJECT
        )
    )
)

internal class NativeRequestIdentityFingerprint(accountType: String) : Fingerprint(
    classFingerprint = NativeBaseRequestDescriptionFingerprint,
    parameters = listOf(),
    returnType = accountType
)

// Stable protobuf extension number, scoped to the Parcelable response adapter.
internal object NativeBrowseResponseFingerprint : Fingerprint(
    parameters = listOf(),
    filters = listOf(literal(58173949)),
    custom = { _, classDef -> "Landroid/os/Parcelable;" in classDef.interfaces }
)

/**
 * Matches the protobuf response field written to the parcel.
 */
internal object NativeBrowseResponsePayloadFingerprint : Fingerprint(
    classFingerprint = NativeBrowseResponseFingerprint,
    name = "writeToParcel",
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = listOf("Lcom/google/protobuf/MessageLite;", "Landroid/os/Parcel;"),
            location = MatchAfterImmediately()
        )
    )
)

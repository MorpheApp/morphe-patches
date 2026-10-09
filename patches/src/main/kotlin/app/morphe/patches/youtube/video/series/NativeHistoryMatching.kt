/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal data class NativeHistoryContract(
    val service: ClassDef,
    val request: ClassDef,
    val factory: Method,
    val capture: Method,
    val dispatch: Method,
    val genericDispatches: List<Method>,
    val routeSetter: Method,
    val continuationSetter: Method,
    val identity: Method,
    val route: FieldReference,
    val continuation: FieldReference,
    val clickTracking: FieldReference,
    val payload: FieldReference,
)

/** Resolve the native browse service, its History request and response members. */
internal fun BytecodePatchContext.resolveNativeHistory(accountType: String): NativeHistoryContract {
    val capture = NativeStreamingBrowseRequestFingerprint.instructionMatches.last().getMethodCalled()
    val request = classDefBy(capture.returnType)

    val description = NativeRequestDescriptionFingerprint(request.type)
    val route = description.instructionMatches[1].instruction.getReference<FieldReference>()!!
    val continuation = description.instructionMatches[3].instruction.getReference<FieldReference>()!!

    val clickTracking = NativeBaseRequestDescriptionFingerprint.instructionMatches[1]
        .instruction.getReference<FieldReference>()!!

    // Some versions have both the three and the four parameter dispatch. Hook all of them.
    val service = NativeBrowseServiceFingerprint.originalClassDef
    val requestBaseType = request.superclass!!
    val genericDispatches = listOf(
        NativeGenericDispatchFingerprint(requestBaseType),
        NativeGenericDispatchWithExtraParameterFingerprint(requestBaseType),
    ).mapNotNull { it.matchOrNull(service)?.originalMethod }.ifEmpty {
        throw PatchException("Could not find native History generic dispatch")
    }

    return NativeHistoryContract(
        service = service,
        request = request,
        factory = NativeRequestFactoryFingerprint(request.type).originalMethod,
        capture = capture,
        dispatch = NativeBrowseDispatchFingerprint(request.type).originalMethod,
        genericDispatches = genericDispatches,
        routeSetter = NativeRequestStringSetterFingerprint(request.type, route.name).originalMethod,
        continuationSetter = NativeRequestStringSetterFingerprint(
            NativeBaseRequestDescriptionFingerprint.originalClassDef.type,
            continuation.name
        ).originalMethod,
        identity = NativeRequestIdentityFingerprint(accountType).originalMethod,
        route = route,
        continuation = continuation,
        clickTracking = clickTracking,
        payload = NativeBrowseResponsePayloadFingerprint.instructionMatches.first()
            .instruction.getReference<FieldReference>()!!,
    )
}

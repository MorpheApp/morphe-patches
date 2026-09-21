package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal data class NativeHistoryContract(
    val service: ClassDef,
    val request: ClassDef,
    val factory: Method,
    val capture: Method,
    val dispatch: Method,
    val genericDispatch: Method,
    val routeSetter: Method,
    val continuationSetter: Method,
    val identity: Method,
    val route: FieldReference,
    val continuation: FieldReference,
    val clickTracking: FieldReference,
    val payload: FieldReference,
)

private const val STRING = "Ljava/lang/String;"

private fun isProtobufMessage(type: String, lookup: (String) -> ClassDef?): Boolean {
    val visited = mutableSetOf<String>()
    fun visit(current: String): Boolean {
        if (current == "Lcom/google/protobuf/MessageLite;") return true
        if (!visited.add(current)) return false
        val definition = lookup(current) ?: return false
        return definition.interfaces.any(::visit) || definition.superclass?.let(::visit) == true
    }
    return visit(type)
}

private fun <T> Iterable<T>.unique(role: String): T =
    singleOrNull()
        ?: throw PatchException("Series Tracker: native History $role is missing or ambiguous")

/** Resolve the native contract from strings, signatures, inheritance and call relationships. */
context(context: BytecodePatchContext)
internal fun resolveNativeHistory(
    service: ClassDef,
    responseAccessors: List<Method>,
    endpointRegistration: Method,
    accountType: String,
    lookup: (String) -> ClassDef,
): NativeHistoryContract {
    val capture =
        NativeAccountRequestFingerprint(accountType).matchAll(service, 1..1).single().originalMethod
    val request = lookup(capture.returnType)
    val hierarchy =
        generateSequence(request) {
                it.superclass
                    ?.takeUnless { type ->
                        type == "Ljava/lang/Object;"
                    }
                    ?.let(lookup)
            }
            .toList()
    fun Fingerprint.inHierarchy() = hierarchy.flatMap { matchAllOrNull(it).orEmpty() }
    val factories = NativeRequestFactoryFingerprint(request.type).matchAllOrNull(service)
    val factory =
        if (factories != null) factories.unique("request factory").originalMethod
        else {
            NativeRequestFactoryFingerprint(request.type, capture.parameterTypes.first().toString())
                .matchAll(service)
                .filter { candidate ->
                    NativeNullContextCallerFingerprint(candidate.originalMethod)
                        .matchOrNull(service) != null
                }
                .unique("nullable-context request factory")
                .originalMethod
        }
    val dispatch =
        NativeBrowseDispatchFingerprint(service.type, request.type)
            .matchAll(service, 1..1)
            .single()
            .originalMethod
    NativeDispatchTargetFingerprint(dispatch).matchAll(service)
    val genericDispatch =
        NativeGenericDispatchFingerprint(request.superclass!!)
            .matchAll(service, 1..1)
            .single()
            .originalMethod

    fun declared(field: FieldReference): FieldReference =
        hierarchy
            .flatMap { it.fields }
            .filter {
                it.name == field.name && it.type == field.type
            }
            .unique("declared ${field.type} field")
            .also {
                if (
                    !AccessFlags.PUBLIC.isSet(it.accessFlags) ||
                        AccessFlags.STATIC.isSet(it.accessFlags)
                ) {
                    throw PatchException("Series Tracker: native History field is not accessible")
                }
            }
    val description =
        NativeRequestDescriptionFingerprint.matchAll(request, 1..1).single().originalMethod
    fun labeledString(label: String): FieldReference {
        val instructions = description.instructionsOrNull!!.toList()
        val index =
            instructions.indices
                .filter {
                    ((instructions[it] as? ReferenceInstruction)?.reference as? StringReference)
                        ?.string == label
                }
                .unique("$label label")
        val field =
            (instructions.getOrNull(index + 1) as? ReferenceInstruction)?.reference
                as? FieldReference
        if (
            instructions.getOrNull(index + 1)?.opcode != Opcode.IGET_OBJECT || field?.type != STRING
        ) {
            throw PatchException("Series Tracker: native History $label field access changed")
        }
        return declared(field)
    }
    val route = labeledString("browseId")
    val continuation = labeledString("continuation")
    val routeSetter =
        NativeRequestStringSetterFingerprint(route)
            .inHierarchy()
            .unique("browse route setter")
            .originalMethod
    NativeHomeRouteSetterFingerprint.match(routeSetter, lookup(routeSetter.definingClass))
    val continuationSetter =
        NativeRequestStringSetterFingerprint(continuation)
            .inHierarchy()
            .unique("continuation setter")
            .originalMethod
    val identity =
        NativeRequestIdentityFingerprint(accountType)
            .inHierarchy()
            .unique("request identity")
            .originalMethod
    val baseDescription =
        NativeBaseRequestDescriptionFingerprint.inHierarchy()
            .unique("base request description")
            .originalMethod
    val clickTracking =
        declared(
            baseDescription.instructionsOrNull!!
                .toList()
                .filter { it.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { it.getReference<FieldReference>() }
                .filter { it.type == "[B" }
                .unique("click tracking field")
        )
    // The native byte-array setters only null-check and store this field. Our bridge always
    // stores a newly allocated, non-null empty array; there is no native setter side effect.
    val clickSetters =
        NativeClickTrackingSetterFingerprint(clickTracking).inHierarchy().map { it.originalMethod }
    if (
        clickSetters.isEmpty() ||
            clickSetters.any { method ->
                method.instructionsOrNull!!.toList().map { it.opcode } !=
                    listOf(Opcode.INVOKE_VIRTUAL, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID) ||
                    method.instructionsOrNull!!
                        .mapNotNull { it.getReference<MethodReference>() }
                        .singleOrNull()
                        ?.toString() != "Ljava/lang/Object;->getClass()Ljava/lang/Class;"
            }
    )
        throw PatchException("Series Tracker: native History click tracking setter changed")

    val responseType =
        responseAccessors.map { it.definingClass }.distinct().unique("response adapter")
    val payload =
        responseAccessors
            .flatMap { it.instructionsOrNull!!.toList() }
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { field ->
                field.definingClass == responseType &&
                    NativeResponseConstructorFingerprint(field).matchOrNull(lookup(responseType)) !=
                        null &&
                    isProtobufMessage(field.type) { type ->
                        if (type.startsWith("Ljava/") || type.startsWith("Landroid/")) null
                        else lookup(type)
                    }
            }
            .distinctBy { it.toString() }
            .unique("response payload")
    val payloadField =
        lookup(payload.definingClass)
            .fields
            .filter { it.toString() == payload.toString() }
            .unique("response payload declaration")
    if (
        !AccessFlags.PUBLIC.isSet(payloadField.accessFlags) ||
            !isProtobufMessage(payload.type) { type ->
                if (type == "Ljava/lang/Object;") null else lookup(type)
            }
    ) {
        throw PatchException("Series Tracker: native History payload is not an accessible protobuf")
    }
    val endpointType =
        endpointRegistration.instructionsOrNull!!
            .toList()
            .filter { it.opcode == Opcode.CONST_CLASS }
            .mapNotNull { it.getReference<TypeReference>()?.type }
            .unique("watch endpoint protobuf")
    if (
        lookup(endpointType).fields.none {
            it.type == "F" && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
    ) {
        throw PatchException(
            "Series Tracker: native History watch endpoint lost its float resume position"
        )
    }
    return NativeHistoryContract(
        service,
        request,
        factory,
        capture,
        dispatch,
        genericDispatch,
        routeSetter,
        continuationSetter,
        identity,
        route,
        continuation,
        clickTracking,
        payload,
    )
}

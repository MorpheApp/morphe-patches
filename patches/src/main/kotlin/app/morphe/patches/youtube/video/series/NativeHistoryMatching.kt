package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal object NativeBrowseServiceFingerprint :
    Fingerprint(
        name = "<init>",
        filters = listOf(string("browse")),
    )

// Stable protobuf extension number, scoped to the Parcelable response adapter.
internal object NativeBrowseResponseFingerprint :
    Fingerprint(
        parameters = emptyList(),
        filters = listOf(literal(58173949)),
        custom = { _, classDef -> "Landroid/os/Parcelable;" in classDef.interfaces },
    )

internal object NativeWatchEndpointFingerprint :
    Fingerprint(
        name = "<clinit>",
        filters = listOf(literal(48687757), methodCall(name = "newSingularGeneratedExtension")),
    )

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
private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

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

private fun Method.instructions() = implementation?.instructions?.toList().orEmpty()

private inline fun <reified T> Method.references() =
    instructions().mapNotNull {
        (it as? ReferenceInstruction)?.reference as? T
    }

private fun Method.hasString(value: String) =
    references<StringReference>().any { it.string == value }

private fun Method.hasShape(args: List<String>, result: String) =
    parameterTypes.map(CharSequence::toString) == args &&
        returnType == result &&
        AccessFlags.PUBLIC.isSet(accessFlags) &&
        !AccessFlags.STATIC.isSet(accessFlags)

/** Resolve the native contract from strings, signatures, inheritance and call relationships. */
internal fun resolveNativeHistory(
    service: ClassDef,
    responseAccessors: List<Method>,
    endpointRegistration: Method,
    accountType: String,
    lookup: (String) -> ClassDef,
): NativeHistoryContract {
    val capture =
        service.methods
            .filter {
                it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == accountType &&
                    it.parameterTypes.last() == STRING &&
                    it.returnType.startsWith("L") &&
                    it.instructions().any { instruction ->
                        instruction.opcode == Opcode.NEW_INSTANCE &&
                            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)
                                ?.type == it.returnType
                    }
            }
            .unique("account-bound request factory")
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
    val methods = hierarchy.flatMap { it.methods }
    val factories = service.methods.filter { it.hasShape(emptyList(), request.type) }
    val factory =
        if (factories.isNotEmpty()) factories.unique("request factory")
        else {
            // Newer hosts pass a nullable request context. Require an existing null call site.
            service.methods
                .filter { candidate ->
                    candidate.hasShape(
                        listOf(capture.parameterTypes.first().toString()),
                        request.type,
                    ) &&
                        service.methods.any { caller ->
                            caller.instructions().zipWithNext().any { (value, call) ->
                                value.opcode == Opcode.CONST_4 &&
                                    (value as? NarrowLiteralInstruction)?.narrowLiteral == 0 &&
                                    call.opcode == Opcode.INVOKE_VIRTUAL &&
                                    (call as? ReferenceInstruction)?.reference.toString() ==
                                        candidate.toString() &&
                                    (call as? FiveRegisterInstruction)?.registerCount == 2 &&
                                    call.registerD == (value as? OneRegisterInstruction)?.registerA
                            }
                        }
                }
                .unique("nullable-context request factory")
        }
    val dispatches = service.methods.filter { it.hasShape(listOf(request.type, EXECUTOR), FUTURE) }
    // The public entry point delegates to the implementation with the same signature shape.
    val dispatch =
        dispatches
            .filter { method ->
                method.references<MethodReference>().any { call ->
                    dispatches.any { it != method && it.toString() == call.toString() }
                }
            }
            .unique("browse dispatch")
    val genericDispatch =
        service.methods
            .filter {
                it.parameterTypes.size in 3..4 &&
                    it.parameterTypes.first() == request.superclass &&
                    it.parameterTypes[2] == EXECUTOR &&
                    it.references<MethodReference>().any { call -> call.returnType == FUTURE } &&
                    it.returnType == FUTURE &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                    !AccessFlags.STATIC.isSet(it.accessFlags)
            }
            .unique("generic dispatch")

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
        request.methods
            .filter { it.hasString("browseId") && it.hasString("continuation") }
            .unique("request description")
    fun labeledString(label: String): FieldReference {
        val instructions = description.instructions()
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
    fun setter(field: FieldReference) =
        methods
            .filter { method ->
                method.hasShape(listOf(STRING), "V") &&
                    method.instructions().any {
                        it.opcode == Opcode.IPUT_OBJECT &&
                            ((it as? ReferenceInstruction)?.reference as? FieldReference)
                                ?.toString() == field.toString()
                    }
            }
            .unique("string setter")
    val routeSetter = setter(route)
    if (!routeSetter.hasString("FEwhat_to_watch")) {
        throw PatchException(
            "Series Tracker: native History browse setter lost its home-route contract"
        )
    }
    val continuationSetter = setter(continuation)
    val identity =
        methods.filter { it.hasShape(emptyList(), accountType) }.unique("request identity")
    val baseDescription =
        methods
            .filter { it.hasString("serviceName") && it.hasString("clickTrackingParams") }
            .unique("base request description")
    val clickTracking =
        declared(
            baseDescription
                .instructions()
                .filter { it.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .filter { it.type == "[B" }
                .unique("click tracking field")
        )
    // The native byte-array setters only null-check and store this field. Our bridge always
    // stores a newly allocated, non-null empty array; there is no native setter side effect.
    val clickSetters = methods.filter { method ->
        method.hasShape(listOf("[B"), "V") &&
            method.references<FieldReference>().any { it.toString() == clickTracking.toString() }
    }
    if (
        clickSetters.isEmpty() ||
            clickSetters.any { method ->
                method.instructions().map { it.opcode } !=
                    listOf(Opcode.INVOKE_VIRTUAL, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID) ||
                    method.references<MethodReference>().singleOrNull()?.toString() !=
                        "Ljava/lang/Object;->getClass()Ljava/lang/Class;"
            }
    )
        throw PatchException("Series Tracker: native History click tracking setter changed")

    val responseType =
        responseAccessors.map { it.definingClass }.distinct().unique("response adapter")
    val payload =
        responseAccessors
            .flatMap { it.instructions() }
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .filter { field ->
                field.definingClass == responseType &&
                    lookup(responseType).methods.any { constructor ->
                        constructor.name == "<init>" &&
                            field.type in constructor.parameterTypes &&
                            constructor.instructions().any {
                                it.opcode == Opcode.IPUT_OBJECT &&
                                    (it as? ReferenceInstruction)?.reference.toString() ==
                                        field.toString()
                            }
                    } &&
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
        endpointRegistration
            .instructions()
            .filter { it.opcode == Opcode.CONST_CLASS }
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type }
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

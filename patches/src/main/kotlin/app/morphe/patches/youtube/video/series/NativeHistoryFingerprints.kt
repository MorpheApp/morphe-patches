package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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

internal class NativeAccountRequestFingerprint(accountType: String) :
    Fingerprint(
        parameters = listOf("L", accountType, "Ljava/lang/String;"),
        returnType = "L",
        filters = listOf(newInstance(type = "L")),
        custom = { method, _ ->
            method.indexOfFirstInstruction(newInstance(type = method.returnType)) >= 0
        },
    )

internal class NativeRequestFactoryFingerprint(requestType: String, contextType: String? = null) :
    Fingerprint(
        parameters = contextType?.let { listOf(it) } ?: emptyList(),
        returnType = requestType,
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

// A nullable context is safe only when the host itself passes null to this factory.
internal class NativeNullContextCallerFingerprint(factory: MethodReference) :
    Fingerprint(
        filters = listOf(literal(0), methodCall(factory, location = MatchAfterImmediately())),
        custom = { method, _ ->
            method.findInstructionIndicesReversed(methodCall(factory)).any { index ->
                if (index == 0) false
                else {
                    val value = method.getInstruction<Instruction>(index - 1)
                    val call = method.getInstruction<Instruction>(index)
                    value.opcode == Opcode.CONST_4 &&
                        (value as? NarrowLiteralInstruction)?.narrowLiteral == 0 &&
                        call.opcode == Opcode.INVOKE_VIRTUAL &&
                        (call as? FiveRegisterInstruction)?.registerCount == 2 &&
                        call.registerD == (value as? OneRegisterInstruction)?.registerA
                }
            }
        },
    )

internal class NativeBrowseDispatchFingerprint(serviceType: String, requestType: String) :
    Fingerprint(
        parameters = listOf(requestType, "Ljava/util/concurrent/Executor;"),
        returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
        filters =
            listOf(
                methodCall(
                    definingClass = serviceType,
                    parameters = listOf(requestType, "Ljava/util/concurrent/Executor;"),
                    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
                )
            ),
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.indexOfFirstInstruction {
                    getReference<MethodReference>()?.let { call ->
                        call.definingClass == serviceType &&
                            call.name != method.name &&
                            call.parameterTypes == method.parameterTypes &&
                            call.returnType == method.returnType
                    } == true
                } >= 0
        },
    )

internal class NativeGenericDispatchFingerprint(requestBase: String) :
    Fingerprint(
        returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
        filters =
            listOf(methodCall(returnType = "Lcom/google/common/util/concurrent/ListenableFuture;")),
        custom = { method, _ ->
            method.parameterTypes.size in 3..4 &&
                method.parameterTypes.first() == requestBase &&
                method.parameterTypes[2] == "Ljava/util/concurrent/Executor;" &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal object NativeRequestDescriptionFingerprint :
    Fingerprint(strings = listOf("browseId", "continuation"))

internal object NativeBaseRequestDescriptionFingerprint :
    Fingerprint(strings = listOf("serviceName", "clickTrackingParams"))

internal class NativeRequestStringSetterFingerprint(field: FieldReference) :
    Fingerprint(
        parameters = listOf("Ljava/lang/String;"),
        returnType = "V",
        filters = listOf(fieldAccess(field, opcode = Opcode.IPUT_OBJECT)),
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal class NativeRequestIdentityFingerprint(accountType: String) :
    Fingerprint(
        parameters = emptyList(),
        returnType = accountType,
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal class NativeClickTrackingSetterFingerprint(field: FieldReference) :
    Fingerprint(
        parameters = listOf("[B"),
        returnType = "V",
        filters = listOf(fieldAccess(field)),
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal class NativeResponseConstructorFingerprint(payload: FieldReference) :
    Fingerprint(
        name = "<init>",
        filters = listOf(fieldAccess(payload, opcode = Opcode.IPUT_OBJECT)),
        custom = { method, _ -> payload.type in method.parameterTypes },
    )

// Validate the delegated implementation as well as the public dispatch entry point.
internal class NativeDispatchTargetFingerprint(dispatch: Method) :
    Fingerprint(
        parameters = dispatch.parameterTypes.map(CharSequence::toString),
        returnType = dispatch.returnType,
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.name != dispatch.name &&
                dispatch.indexOfFirstInstruction(methodCall(method)) >= 0
        },
    )

internal object NativeHomeRouteSetterFingerprint :
    Fingerprint(
        parameters = listOf("Ljava/lang/String;"),
        returnType = "V",
        filters = listOf(string("FEwhat_to_watch")),
    )

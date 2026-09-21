package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
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

// The resolver additionally verifies that the null constant is the factory argument.
internal class NativeNullContextCallerFingerprint(factory: MethodReference) :
    Fingerprint(
        filters = listOf(literal(0), methodCall(factory, location = MatchAfterImmediately()))
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
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

// The 21.38 host has a fourth object parameter on the generic dispatch method.
internal class NativeGenericDispatchFingerprint(requestBase: String) :
    Fingerprint(
        parameters = listOf(requestBase, "L", "Ljava/util/concurrent/Executor;"),
        returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
        filters =
            listOf(methodCall(returnType = "Lcom/google/common/util/concurrent/ListenableFuture;")),
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal class NativeGenericDispatchWithExtraParameterFingerprint(requestBase: String) :
    Fingerprint(
        parameters = listOf(requestBase, "L", "Ljava/util/concurrent/Executor;", "L"),
        returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
        filters =
            listOf(methodCall(returnType = "Lcom/google/common/util/concurrent/ListenableFuture;")),
        custom = { method, _ ->
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

// Resolve an already identified call target by its exact signature.
internal class NativeDispatchTargetFingerprint(target: MethodReference) :
    Fingerprint(
        definingClass = target.definingClass,
        name = target.name,
        parameters = target.parameterTypes.map(CharSequence::toString),
        returnType = target.returnType,
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal object NativeHomeRouteSetterFingerprint :
    Fingerprint(
        parameters = listOf("Ljava/lang/String;"),
        returnType = "V",
        filters = listOf(string("FEwhat_to_watch")),
    )

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object MediaSessionFingerprint :
    Fingerprint(
        filters =
            listOf(
                methodCall(
                    definingClass = "Landroid/media/session/MediaSession;",
                    name = "setMetadata",
                    parameters = listOf("Landroid/media/MediaMetadata;"),
                )
            )
    )

internal object BrowseFragmentFingerprint :
    Fingerprint(
        parameters =
            listOf(
                "Landroid/view/LayoutInflater;",
                "Landroid/view/ViewGroup;",
                "Landroid/os/Bundle;",
            ),
        returnType = "Landroid/view/View;",
        filters =
            listOf(string("Browse Fragment was given a navigation endpoint without browse data.")),
    )

internal object AccountIdentityFingerprint :
    Fingerprint(
        name = "toString",
        strings = listOf("AccountIdentity{getId=", ", isIncognito="),
    )

internal object SignedOutIdentityFingerprint : Fingerprint(strings = listOf("PseudonymousIdentity"))

internal object CurrentAccountProviderFingerprint :
    Fingerprint(strings = listOf("NEXT_INCOGNITO_SESSION_INDEX"))

internal class RegisteredParseFingerprint(parse: MethodReference) :
    Fingerprint(
        definingClass = parse.definingClass,
        name = "parseFrom",
        parameters =
            parse.parameterTypes.map(CharSequence::toString) +
                "Lcom/google/protobuf/ExtensionRegistryLite;",
        returnType = parse.returnType,
    )

internal object GeneratedRegistryFingerprint :
    Fingerprint(
        definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
        name = "getGeneratedRegistry",
        parameters = emptyList(),
        returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
    )

internal object HistoryNavigationBuilderFingerprint :
    Fingerprint(
        definingClass = "Lapp/morphe/extension/youtube/series/HistoryNavigation;",
        name = "buildNative",
        parameters = listOf("[B"),
        returnType = "Ljava/lang/Object;",
    )

internal object NavigationTabCreatedFingerprint :
    Fingerprint(
        definingClass = "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;",
        name = "navigationTabCreated",
        parameters = listOf("L", "Landroid/view/View;"),
        returnType = "V",
    )

internal class BrowseRouteFingerprint(endpointType: String) :
    Fingerprint(
        classFingerprint = BrowseFragmentFingerprint,
        filters =
            listOf(
                methodCall(
                    opcode = Opcode.INVOKE_STATIC,
                    parameters = listOf(endpointType),
                    returnType = "Ljava/lang/String;",
                )
            ),
    )

internal object ToolbarMenuFingerprint :
    Fingerprint(
        definingClass = "Landroid/support/v7/widget/Toolbar;",
        parameters = emptyList(),
        returnType = "Landroid/view/Menu;",
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )

internal class ControllerVideoIdFingerprint(accessor: MethodReference) :
    Fingerprint(
        parameters = emptyList(),
        returnType = "Ljava/lang/String;",
        filters = listOf(methodCall(accessor)),
        custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
    )

internal class IdentityFieldGetterFingerprint(field: FieldReference) :
    Fingerprint(
        parameters = emptyList(),
        returnType = field.type,
        filters = listOf(fieldAccess(field)),
        custom = { method, _ -> method.name != "toString" },
    )

internal class IdentityInterfaceMethodFingerprint(method: MethodReference) :
    Fingerprint(
        name = method.name,
        parameters = method.parameterTypes.map(CharSequence::toString),
        returnType = method.returnType,
    )

// Signed-out identities return false for incognito, but true for unauthenticated.
internal class SignedOutIncognitoFingerprint(candidate: MethodReference) :
    Fingerprint(
        name = candidate.name,
        parameters = emptyList(),
        returnType = "Z",
        filters = listOf(literal(0), opcode(Opcode.RETURN, location = MatchAfterImmediately())),
        custom = { method, _ ->
            method.instructionsOrNull?.toList()?.let {
                it.size == 2 && it.first().opcode == Opcode.CONST_4
            } == true
        },
    )

internal class CurrentAccountGetterFingerprint(accountType: String) :
    Fingerprint(
        parameters = emptyList(),
        returnType = accountType,
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) && method.implementation != null
        },
    )

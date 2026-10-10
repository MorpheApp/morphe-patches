/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.youtube.video.information.PlayerInitFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object MediaSessionFingerprint : Fingerprint(
    filters = listOf(
        methodCall(smali = "Landroid/media/session/MediaSession;->setMetadata(Landroid/media/MediaMetadata;)V")
    )
)

internal object BrowseFragmentFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;",
    ),
    returnType = "Landroid/view/View;",
    filters = listOf(
        string("Browse Fragment was given a navigation endpoint without browse data.")
    )
)

internal object AccountIdentityFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("AccountIdentity{getId="),
        string(", isIncognito=")
    )
)

internal object SignedOutIdentityFingerprint : Fingerprint(
    filters = listOf(
        string("PseudonymousIdentity")
    )
)

internal object CurrentAccountProviderFingerprint : Fingerprint(
    filters = listOf(
        string("NEXT_INCOGNITO_SESSION_INDEX")
    )
)

internal class RegisteredParseFingerprint(parse: MethodReference) : Fingerprint(
    definingClass = parse.definingClass,
    name = "parseFrom",
    parameters = parse.parameterTypes.map(CharSequence::toString) +
            "Lcom/google/protobuf/ExtensionRegistryLite;",
    returnType = parse.returnType,
)

internal object GeneratedRegistryFingerprint : Fingerprint(
    definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
    name = "getGeneratedRegistry",
    parameters = listOf(),
    returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
)

internal object HistoryNavigationBuilderFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/youtube/series/HistoryNavigation;",
    name = "buildNative",
    parameters = listOf("[B"),
    returnType = "Ljava/lang/Object;",
)

internal object NavigationTabCreatedFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/youtube/patches/NavigationBarPatch;",
    name = "navigationTabCreated",
    parameters = listOf(
        "Lapp/morphe/extension/youtube/shared/NavigationBar\$NavigationButton;",
        "Landroid/view/View;"
    ),
    returnType = "V",
)

internal class BrowseRouteFingerprint(endpointType: String) : Fingerprint(
    classFingerprint = BrowseFragmentFingerprint,
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf(endpointType),
            returnType = "Ljava/lang/String;",
        )
    )
)

internal object ToolbarMenuFingerprint : Fingerprint(
    definingClass = "Landroid/support/v7/widget/Toolbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Landroid/view/Menu;",
)

internal class ControllerVideoIdFingerprint(accessor: MethodReference) : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        methodCall(accessor)
    )
)

/**
 * Getter of an account identity field. toString also reads the field, but not first.
 */
internal class IdentityFieldGetterFingerprint(field: FieldReference) : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = field.type,
    filters = listOf(
        fieldAccess(field, location = MatchFirst())
    )
)

internal class IdentityInterfaceMethodFingerprint(method: MethodReference) : Fingerprint(
    name = method.name,
    parameters = method.parameterTypes.map(CharSequence::toString),
    returnType = method.returnType,
)

/**
 * Signed-out identities return false for incognito, but true for unauthenticated.
 */
internal class SignedOutIncognitoFingerprint(candidate: MethodReference) : Fingerprint(
    name = candidate.name,
    parameters = listOf(),
    returnType = "Z",
    filters = listOf(
        literal(0, location = MatchFirst()),
        opcode(Opcode.RETURN, location = MatchAfterImmediately())
    )
)

/**
 * Each current-account provider has one getter for the identity.
 * The getter of one provider is synchronized and the other is not.
 */
internal class CurrentAccountGetterFingerprint(accountType: String) : Fingerprint(
    parameters = listOf(),
    returnType = accountType,
)

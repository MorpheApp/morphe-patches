package app.morphe.patches.youtube.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SetThemeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "app_theme_appearance_dark"),
    )
)

internal object CairoFragmentConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45532100L)
    )
)

/**
 * Synthetic Runnable from YT settings intent handling, fired after the PreferenceScreen builds.
 */
internal object SettingsPreferenceScreenSyntheticFingerprint : Fingerprint(
    name = "run",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        string(":android:show_fragment_args"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Landroidx/preference/PreferenceScreen;"
        ),
        opcode(Opcode.RETURN_VOID)
    )
)

/**
 * Looks up the account scoped command router, which only the command router of an activity does.
 */
internal object CommandRouterFragmentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    strings = listOf("com.google.android.apps.youtube.app.endpoint.routers.AccountScopeCommandRouterFragment")
)

/**
 * The command router of an activity, created with it.
 */
internal object CommandRouterConstructorFingerprint : Fingerprint(
    classFingerprint = CommandRouterFragmentFingerprint,
    name = "<init>",
    parameters = listOf("Landroid/app/Activity;", "L", "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Object;-><init>()V")
    )
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object RedditActivityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        string("android:support:lifecycle")
    )
)

internal object GoogleSignInFunctionFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "continue_with_google"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            returnType = "V",
            parameters = listOf(
                "I",
                "L",
                "Ljava/lang/String;",
                "Lkotlin/jvm/functions/Function0;",
                "L",
                "Z",
                "Ljava/lang/String;",
                "Z",
                "L",
                "I",
                "I"
            ),
            location = MatchAfterWithin(20)
        )
    ),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags)
    }
)

// 2026.25.0+
internal object StartUrlActivityFingerprint : Fingerprint(
    parameters = listOf(
        "L",
        "Landroid/app/Activity;",
        "Landroid/net/Uri;",
        "Landroid/os/Bundle;",
        "Z",
        "I"
    ),
    filters = listOf(
        methodCall(smali = "Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;"),
        string("android.intent.action.VIEW"),
        methodCall(
            parameters = listOf(
                "Landroid/app/Activity;",
                "Landroid/net/Uri;",
                "I",
                "Ljava/lang/String;",
                "Landroid/os/Bundle;",
                "Z"
            )
        )
    ),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags)
    }
)

internal object PreferenceDestinationLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/screen/settings/preferences/",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Lcom/reddit/domain/settings/Destination;"),
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        string("settingIntentProvider")
    )
)

// 2026.29.0 and older
internal object PreferenceManagerLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        opcode(Opcode.CONST),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/Context;->getDrawable(I)Landroid/graphics/drawable/Drawable;",
            location = MatchAfterWithin(3)
        ),
        opcode(
            Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.CONST,
            location = MatchAfterWithin(10)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;",
            location = MatchAfterWithin(3)
        ),
        opcode(
            Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        newInstance($$"Lcom/reddit/screen/settings/preferences/PreferencesPresenter$checkIfShouldShowImpressumOption$")
    )
)

internal object WebBrowserActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/webembed/browser/WebBrowserActivity;",
    name = "onCreate",
    returnType = "V",
    filters = listOf(
        anyInstruction(
            opcode(Opcode.INVOKE_SUPER),
            opcode(Opcode.INVOKE_SUPER_RANGE),
        )
    ),
    strings = listOf("com.reddit.extra.initial_url")
)

internal object GooglePlayUpdateCheckFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    ),
    filters = listOf(
        checkCast("Lcom/reddit/appupdate/GooglePlayImmediateUpdateCheck$"),
        string("PlayCore")
    )
)

internal object PlayStoreVerificationFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(
        "Landroid/content/Context;"
    ),
    filters = listOf(
        string("Play Store package is not found.")
    )
)

internal object CheckIntegrityPlayStoreFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf(
        "Landroid/content/Context;"
    ),
    filters = listOf(
        string("com.android.vending")
    )
)

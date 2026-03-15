/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object RedditActivityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        string("android:support:lifecycle")
    )
)

internal object PreferenceDestinationFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/screen/settings/preferences/",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Lcom/reddit/domain/settings/Destination;"),
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        string("settingIntentProvider")
    )
)

internal object PreferenceManagerFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
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
        newInstance("Lcom/reddit/screen/settings/preferences/PreferencesPresenter\$checkIfShouldShowImpressumOption$")
    )
)

internal object RedditInternalFeaturesFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        opcode(Opcode.CONST_4),
        opcode(Opcode.CONST_STRING),
        string("%s.%d"),
        string("RELEASE")
    )
)

internal object WebBrowserActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/webembed/browser/WebBrowserActivity;",
    name = "onCreate",
    returnType = "V",
    filters = listOf(
        methodCall(smali = "Landroid/app/Activity;->getIntent()Landroid/content/Intent;")
    ),
    strings = listOf("com.reddit.extra.initial_url")
)
/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3905
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.commandrouter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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

/**
 * Runs one command with its context, by the command router itself if no other router handles the command.
 */
internal object CommandRouterExecuteCommandFingerprint : Fingerprint(
    classFingerprint = CommandRouterFragmentFingerprint,
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "this",
            parameters = listOf("L", "Ljava/util/Map;"),
            returnType = "V"
        )
    )
)

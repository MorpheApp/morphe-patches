/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4570
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.onthego

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Name of the "Skip to next" media session action, which is only shown while On-the-go is active.
 * Matches the getter of the action name.
 */
internal object OnTheGoSkipToNextActionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("com.google.android.youtube.SKIP_TO_NEXT")
)

/**
 * The first parameter is the On-the-go state.
 */
internal object OnTheGoSkipToNextActionConstructorFingerprint : Fingerprint(
    classFingerprint = OnTheGoSkipToNextActionFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    name = "<init>"
)

internal fun getOnTheGoStateConstructorFingerprint(stateClass: String) = object : Fingerprint(
    definingClass = stateClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    name = "<init>",
    filters = listOf(
        methodCall(smali = "Ljava/lang/Object;-><init>()V"),
        newInstance("Ljava/util/concurrent/atomic/AtomicBoolean;")
    )
) {}

/**
 * Sets if On-the-go is active.
 */
internal fun getOnTheGoStateSetterFingerprint(stateClass: String) = object : Fingerprint(
    definingClass = stateClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(smali = "Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V"),
        methodCall(
            smali = "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;",
            location = MatchAfterWithin(4)
        )
    )
) {}

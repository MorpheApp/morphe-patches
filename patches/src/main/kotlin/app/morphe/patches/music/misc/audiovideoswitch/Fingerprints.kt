/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The wrapper every part of the app reads its preferences through.
 */
internal object SharedPreferencesGetBooleanFingerprint : Fingerprint(
    interfaces = listOf("Landroid/content/SharedPreferences;"),
    name = "getBoolean",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z")
)

/**
 * Preferences that prefix every key with the account, like 'Don't play music videos'.
 */
internal object AccountPreferencesConstructorFingerprint : Fingerprint(
    classFingerprint = SharedPreferencesGetBooleanFingerprint,
    name = "<init>",
    returnType = "V",
)

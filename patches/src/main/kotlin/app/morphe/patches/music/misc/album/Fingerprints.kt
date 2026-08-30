/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.album

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The wrapper every part of the app reads its preferences through, which is the single place the
 * built-in 'Don't play music videos' setting can be reported as off from.
 */
internal object SharedPreferencesGetBooleanFingerprint : Fingerprint(
    name = "getBoolean",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z"),
    custom = { _, classDef ->
        classDef.interfaces.contains("Landroid/content/SharedPreferences;")
    }
)

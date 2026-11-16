/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.Fingerprint
import app.morphe.patches.music.misc.album.SharedPreferencesGetBooleanFingerprint

/**
 * Preferences that prefix every key with the account, like 'Don't play music videos'.
 */
internal object AccountPreferencesConstructorFingerprint : Fingerprint(
    classFingerprint = SharedPreferencesGetBooleanFingerprint,
    name = "<init>",
    returnType = "V",
)

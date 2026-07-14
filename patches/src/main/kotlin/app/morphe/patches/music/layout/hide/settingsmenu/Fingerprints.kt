/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.settingsmenu

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.Opcode

private const val SETTINGS_HEADERS_FRAGMENT_CLASS =
    "Lcom/google/android/apps/youtube/music/settings/fragment/SettingsHeadersFragment;"

/**
 * YT Music top-level Settings fragment. onCreatePreferences reassigns p0 to the peer object at
 * entry, so the captured iget-object recovers the original fragment from `peer.fragment` at exit.
 */
internal object SettingsHeadersOnCreatePreferencesFingerprint : Fingerprint(
    definingClass = SETTINGS_HEADERS_FRAGMENT_CLASS,
    name = "onCreatePreferences",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = SETTINGS_HEADERS_FRAGMENT_CLASS
        )
    )
)

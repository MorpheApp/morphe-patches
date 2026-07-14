/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.settingsmenu

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Preference.setTitle(CharSequence). setSummary shares the parameter type but is not final.
 */
internal object PreferenceSetTitleFingerprint : Fingerprint(
    definingClass = "Landroidx/preference/Preference;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/CharSequence;"),
    returnType = "V",
)

/**
 * Preference.setVisible(boolean). Only (Z)V setter that stores + notifies via a listener field.
 */
internal object PreferenceSetVisibleFingerprint : Fingerprint(
    definingClass = "Landroidx/preference/Preference;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z"),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this"
        )
    )
)

/**
 * PreferenceGroup.getPreference(int). The captured iget yields the backing list field ref.
 */
internal object PreferenceGroupGetPreferenceFingerprint : Fingerprint(
    definingClass = "Landroidx/preference/PreferenceGroup;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("I"),
    returnType = "Landroidx/preference/Preference;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;"
        )
    )
)

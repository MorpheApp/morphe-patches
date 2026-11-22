/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5161
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.privatebrowsing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags.FINAL
import com.android.tools.smali.dexlib2.AccessFlags.PUBLIC
import com.android.tools.smali.dexlib2.Opcode

/**
 * Incognito revamp experiment is not the control variant.
 * Selects between Anonymous Browsing (false) and Incognito (true) everywhere in the app.
 */
internal object IncognitoRevampEnabledFingerprint : Fingerprint(
    accessFlags = listOf(PUBLIC, FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        checkCast("Lcom/reddit/incognito/IncognitoRevampM1Variant;"),
        fieldAccess(definingClass = "Lcom/reddit/incognito/IncognitoRevampM1Variant;", name = "CONTROL"),
        opcode(Opcode.IF_EQ)
    )
)

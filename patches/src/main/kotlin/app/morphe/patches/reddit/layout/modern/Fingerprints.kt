/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
package app.morphe.patches.reddit.layout.modern

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object HomeRevampVariantFingerprint : Fingerprint(
    definingClass = "/HomeRevampVariant;",
    name = "isEnabled",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf()
)
